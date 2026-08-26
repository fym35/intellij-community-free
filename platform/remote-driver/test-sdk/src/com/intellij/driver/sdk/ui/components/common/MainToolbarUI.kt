package com.intellij.driver.sdk.ui.components.common

import com.intellij.driver.sdk.step
import com.intellij.driver.sdk.ui.Finder
import com.intellij.driver.sdk.ui.boundsOnScreen
import com.intellij.driver.sdk.ui.components.ComponentData
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.UiComponent.Companion.waitFound
import com.intellij.driver.sdk.ui.components.elements.ActionButtonUi
import com.intellij.driver.sdk.ui.components.elements.accessibleList
import com.intellij.driver.sdk.ui.components.elements.popup
import com.intellij.driver.sdk.ui.components.settings.settingsDialog
import com.intellij.openapi.util.SystemInfo
import java.awt.Point
import java.awt.Rectangle

val Finder.mainToolbar: MainToolbarUI
  get() =
    x("//div[@class='MainToolbar']", MainToolbarUI::class.java)


/**
 * On Linux without DISPLAY, we run xvfb without window manager and in this case header is missing and we fallback to maintoolbar
 */
val Finder.toolbar: UiComponent
  get() = if (SystemInfo.isLinux && System.getenv("DISPLAY") == null) {
    mainToolbar
  } else {
    toolbarHeader
  }


private const val MIN_EMPTY_MAIN_TOOLBAR_AREA_WIDTH = 24

/**
 * Bounds of the main toolbar's own groups, left to right. Their gaps are the toolbar's free space:
 * `MainToolbar` lays the groups out with [com.intellij.ui.components.panels.HorizontalLayout], so nothing
 * at all is painted between them.
 */
private fun Finder.mainToolbarGroupBounds(): List<Rectangle> =
  xx("//div[@class='MainToolbar']/div").list()
    .map { it.boundsOnScreen }
    .filter { it.width > 0 } // an empty group occupies nothing, but would still split the gap it sits in
    .sortedBy { it.x }

/**
 * The middle of the widest widget-free run of the main toolbar, in screen coordinates, or `null` when the
 * toolbar is too crowded to have a [MIN_EMPTY_MAIN_TOOLBAR_AREA_WIDTH] px wide gap.
 *
 * Use this instead of guessing a point: the New UI centers the run/debug widget group, so the middle of the
 * toolbar is the least likely place to be free.
 *
 * Only the gaps *between* `MainToolbar`'s own groups qualify. `MainToolbar` itself carries the window-move
 * and click-transparency listeners, while its per-group `MyActionToolbarImpl` children swallow clicks into
 * their own popup handler — so a point inside a group's internal padding looks empty but neither drags nor
 * maximizes the window.
 */
fun Finder.emptyMainToolbarAreaPointOnScreenOrNull(): Point? {
  val toolbarBounds = mainToolbar.boundsOnScreen
  val groups = mainToolbarGroupBounds()
  val rightEdge = Rectangle(toolbarBounds.x + toolbarBounds.width, toolbarBounds.y, 0, toolbarBounds.height)

  var freeStart = toolbarBounds.x
  var widestStart = toolbarBounds.x
  var widestEnd = toolbarBounds.x
  for (group in groups + rightEdge) {
    if (group.x - freeStart > widestEnd - widestStart) {
      widestStart = freeStart
      widestEnd = group.x
    }
    freeStart = maxOf(freeStart, group.x + group.width)
  }

  if (widestEnd - widestStart < MIN_EMPTY_MAIN_TOOLBAR_AREA_WIDTH) return null
  return Point((widestStart + widestEnd) / 2, toolbarBounds.y + toolbarBounds.height / 2)
}

/**
 * The same as [emptyMainToolbarAreaPointOnScreenOrNull], but fails instead of returning `null`.
 */
fun Finder.emptyMainToolbarAreaPointOnScreen(): Point =
  emptyMainToolbarAreaPointOnScreenOrNull()
  ?: error("No free area of $MIN_EMPTY_MAIN_TOOLBAR_AREA_WIDTH px or more in the main toolbar " +
           "${mainToolbar.boundsOnScreen}, toolbar groups: ${mainToolbarGroupBounds()}")

class MainToolbarUI(data: ComponentData) : UiComponent(data) {
  val buildButton: UiComponent get() = x("//div[@myicon='build.svg']")
  val runButton: UiComponent get() = x("//div[@myicon='run.svg']")
  val debugButton: UiComponent get() = x("//div[@myicon='debug.svg']")
  val moreButton: UiComponent get() = x("//div[@myicon='moreVertical.svg']")
  val searchButton: UiComponent get() = x("//div[@myicon='search.svg']")
  val stopButton: UiComponent get() = x("//div[@myicon='stop.svg']")
  val settingsButton: UiComponent get() = x("//div[contains(@myaction, 'Settings')]")
  val runWidget get() = x(ActionButtonUi::class.java) { contains(byJavaClass("com.intellij.execution.ui.RedesignedRunConfigurationSelector")) }
  val cwmButton get() = x { byTooltip("Code With Me") }

  fun projectWidget(projectName: String): AbstractToolbarComboUi =
    abstractToolbarCombo { and(byType("com.intellij.openapi.wm.impl.AbstractToolbarCombo"), contains(byVisibleText(projectName))) }

  fun vcsWidget(branchName: String = "Version"): AbstractToolbarComboUi =
    abstractToolbarCombo { and(byType("com.intellij.openapi.wm.impl.AbstractToolbarCombo"), contains(byVisibleText(branchName))) }

  fun gitVcsWidget(): AbstractToolbarComboUi =
    abstractToolbarCombo { byType("com.intellij.vcs.git.frontend.widget.GitBranchToolbarComboButton") }
}

fun IdeaFrameUI.openSettingsViaToolbar() {
  step("Open Settings via Toolbar") {
    mainToolbar.settingsButton.click()
    popup().accessibleList().clickItem("Settings", fullMatch = false)
  }
  step("Wait Settings window is opened") {
    settingsDialog().waitFound()
  }
}

val MainToolbarUI.rerunButton get() = x { contains(byAccessibleName("Rerun")) }
val MainToolbarUI.resumeButton get() = x { contains(byAccessibleName("Resume")) }
val MainToolbarUI.pauseButton get() = x { contains(byAccessibleName("Pause")) }
val MainToolbarUI.restartDebugButton get() = x { contains(byAccessibleName("Restart Debug")) }
val MainToolbarUI.stopButton get() = x { contains(byAccessibleName("Stop")) }