// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.lsp.impl

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.modules
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.platform.project.projectId
import com.intellij.python.pyright.BasedpyrightPyToolFrontend
import com.intellij.python.pyright.PyrightPyToolFrontend
import com.intellij.python.pytools.common.PyToolApi
import com.intellij.python.pytools.common.PyToolRequest
import com.intellij.python.pytools.common.PyToolSetEnabledRequest
import com.intellij.python.pytools.frontend.PyToolFrontend
import com.intellij.python.pytools.frontend.PyToolsFrontendState
import com.intellij.python.pytools.frontend.lsp.PyLspTool
import com.intellij.python.pytools.frontend.ui.configuration.PyExternalToolsConfigurable
import com.intellij.python.ruff.RuffPyToolFrontend
import com.intellij.python.ty.TyPyToolFrontend
import com.jetbrains.python.PyBundle
import com.jetbrains.python.packaging.common.PythonPackageManagementListener
import com.jetbrains.python.packaging.management.PythonPackageManager
import com.jetbrains.python.sdk.findPythonSdk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.annotations.ApiStatus
import kotlin.time.Duration.Companion.seconds

private const val NOTIFICATION_GROUP_ID = "Python LSP Tools"
private const val DONT_ASK_PROPERTY_PREFIX = "python.lsp.tool.dont.ask."

private fun lspTools(): List<PyLspTool<*>> = listOf(
  RuffPyToolFrontend.getInstance(),
  //PyreflyPyTool.getInstance(),
  BasedpyrightPyToolFrontend.getInstance(),
  PyrightPyToolFrontend.getInstance(),
  TyPyToolFrontend.getInstance(),
)

/**
 * Service that detects LSP tools installed in the user's virtual environment
 * and shows a balloon notification offering to enable them.
 *
 * Also listens for package installation events to suggest enabling tools when installed.
 */
@ApiStatus.Internal
@Service(Service.Level.PROJECT)
class PyLspToolAdvertiserService(private val project: Project, private val cs: CoroutineScope) {

  private val shownForTools = mutableSetOf<String>()

  init {
    // Subscribe to package management events to detect when LSP tools are installed
    project.messageBus.connect(cs).subscribe(
      PythonPackageManager.PACKAGE_MANAGEMENT_TOPIC,
      object : PythonPackageManagementListener {
        override fun packagesChanged(sdk: Sdk) {
          thisLogger().debug("Packages changed for SDK '${sdk.name}', triggering LSP tool check")
          // Defer until no modal dialog is open. If the user installs a tool from within
          // the settings dialog (modal), the settings haven't been applied yet at this point,
          // so isEnabled() would return false and trigger a spurious notification. Scheduling
          // with ModalityState.nonModal() means the check runs only after the dialog is
          // closed and settings are applied, so isEnabled() reflects the user's choice.
          ApplicationManager.getApplication().invokeLater({
            cs.launch { checkAndAdvertise() }
          }, ModalityState.nonModal())
        }
      }
    )
  }

  /**
   * Checks for installed LSP tools and shows notifications for any that are found
   * but not yet enabled.
   */
 suspend fun checkAndAdvertise() {
    val installedPackages = getInstalledPackages()
    val pyToolsState = PyToolsFrontendState.getInstance(project)

    val toolsToAdvertise = lspTools().mapNotNull { tool ->
      val isInstalled = tool.packageName.name in installedPackages
      val name = tool.presentableName
      val isEnabled = pyToolsState.isEnabled(tool.toolId)
      if (isInstalled && (isEnabled || isDontAskSet(tool))) {
        thisLogger().debug("LSP tool '$name': installed=true, enabled=$isEnabled, dontAsk=${isDontAskSet(tool)}")
      }
      // shownForTools (keyed on the stable presentable name) suppresses repeat notifications when
      // checkAndAdvertise re-runs on package-change events.
      if (!isInstalled || isEnabled || isDontAskSet(tool) || name in shownForTools) {
        return@mapNotNull null
      }
      tool
    }

    if (toolsToAdvertise.size > 1) {
      for (tool in toolsToAdvertise) {
        thisLogger().info("LSP tool '${tool.presentableName}' installed but not enabled")
        shownForTools.add(tool.presentableName)
      }
      showMultipleToolsNotification(toolsToAdvertise)
    }
    else if (toolsToAdvertise.size == 1) {
      val tool = toolsToAdvertise.single()
      thisLogger().info("LSP tool '${tool.presentableName}' installed but not enabled, showing notification")
      shownForTools.add(tool.presentableName)
      showNotification(tool)
    }
  }

  private suspend fun getInstalledPackages(): Set<String> = project.modules
    .mapNotNull { it.findPythonSdk() }
    .distinct()
    .also { sdks -> thisLogger().debug("Scanning ${sdks.size} SDK(s) for installed packages") }
    .flatMapTo(mutableSetOf()) { sdk ->
      PythonPackageManager
        .forSdk(project, sdk)
        .listInstalledPackages()
        .map { it.name.lowercase() }
    }

  private fun showMultipleToolsNotification(tools: List<PyLspTool<*>>) {
    val toolNames = tools.dropLast(1).joinToString { it.presentableName } +
                    " and " + tools.last().presentableName
    thisLogger().info("Notifying user about multiple LSP tools: $toolNames")
    val notification = NotificationGroupManager.getInstance()
      .getNotificationGroup(NOTIFICATION_GROUP_ID)
      .createNotification(
        PyBundle.message("lsp.tool.advertiser.multiple.title"),
        PyBundle.message("lsp.tool.advertiser.multiple.message", toolNames),
        NotificationType.INFORMATION
      )
      .addAction(NotificationAction.createSimpleExpiring(PyBundle.message("lsp.tool.advertiser.settings")) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, PyExternalToolsConfigurable::class.java)
      })
      .addAction(NotificationAction.createSimpleExpiring(PyBundle.message("lsp.tool.advertiser.ignore")) {
        for (tool in tools) {
          setDontAsk(tool)
        }
      })

    notification.notify(project)
  }

  private fun showNotification(tool: PyLspTool<*>) {
    val name = tool.presentableName
    thisLogger().info("Notifying user about LSP tool: $name")
    val notification = NotificationGroupManager.getInstance()
      .getNotificationGroup(NOTIFICATION_GROUP_ID)
      .createNotification(
        PyBundle.message("lsp.tool.advertiser.title", name),
        PyBundle.message("lsp.tool.advertiser.message", name),
        NotificationType.INFORMATION
      )
      .setIcon(tool.icon)
      .addAction(NotificationAction.createSimpleExpiring(PyBundle.message("lsp.tool.advertiser.yes")) {
        // Mirror the External Tools page's "Enable" toggle: flip the framework-level state and
        // run the tool's lifecycle hook (which starts the LSP server).
        cs.launch {
          val state = PyToolApi.getInstance().setEnabled(
            PyToolSetEnabledRequest(PyToolRequest(project.projectId(), tool.toolId), true),
          )
          PyToolsFrontendState.getInstance(project).apply(
            com.intellij.python.pytools.common.PyToolEnabledStateDto(state.toolId, state.enabled),
          )
          tool.onEnabledChanged(project, true)
        }
      })
      .addAction(NotificationAction.createSimpleExpiring(PyBundle.message("lsp.tool.advertiser.no")) {
        setDontAsk(tool)
      })
      .addAction(NotificationAction.createSimpleExpiring(PyBundle.message("lsp.tool.advertiser.settings")) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, PyExternalToolsConfigurable::class.java)
      })

    notification.notify(project)
  }

  private fun isDontAskSet(tool: PyToolFrontend): Boolean {
    return PropertiesComponent.getInstance(project).getBoolean(DONT_ASK_PROPERTY_PREFIX + tool.presentableName, false)
  }

  private fun setDontAsk(tool: PyToolFrontend) {
    thisLogger().debug("Setting don't-ask for LSP tool: ${tool.presentableName}")
    PropertiesComponent.getInstance(project).setValue(DONT_ASK_PROPERTY_PREFIX + tool.presentableName, true)
  }

  companion object {
    fun getInstance(project: Project): PyLspToolAdvertiserService =
      project.service<PyLspToolAdvertiserService>()
  }
}

class PyLspToolAdvertiserStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    return
    //thisLogger().debug("PyLspToolAdvertiserStartupActivity: waiting 15s before first check")
    //delay(15.seconds)
    //PyLspToolAdvertiserService.getInstance(project).checkAndAdvertise()
  }
}
