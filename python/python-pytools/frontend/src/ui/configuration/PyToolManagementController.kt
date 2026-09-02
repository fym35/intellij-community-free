// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.pytools.frontend.ui.configuration

import com.intellij.openapi.observable.properties.AtomicProperty
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.NlsSafe
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.project.projectId
import com.intellij.python.pytools.common.PyToolApi
import com.intellij.python.pytools.common.PyToolDependencyGroupDto
import com.intellij.python.pytools.common.PyToolId
import com.intellij.python.pytools.common.PyToolOperationResultDto
import com.intellij.python.pytools.common.PyToolRequest
import com.intellij.python.pytools.common.PyToolSdkDto
import com.intellij.python.pytools.common.PyToolSdkInstallRequest
import com.intellij.python.pytools.common.PyToolSdkOperationResultDto
import com.intellij.python.pytools.common.PyToolSdkRequest
import com.intellij.python.pytools.common.PyToolStateDto
import com.intellij.python.pytools.common.PyToolsRequest
import com.intellij.python.pytools.frontend.statistics.PyToolActionSource
import com.intellij.python.pytools.frontend.statistics.PyToolUsagesCollector
import com.intellij.python.pytools.frontend.ui.PyToolsUiBundle
import com.intellij.ui.dsl.listCellRenderer.LcrInitParams
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.swing.JComponent

/** Executes Python-tool operations through the backend RPC API and keeps frontend row state current. */
internal class PyToolManagementController(
  private val project: Project,
  private val onStateChanged: () -> Unit,
  private val refreshRow: (ToolRow) -> Unit,
) {
  private var scope: CoroutineScope? = null

  val uvAvailable: AtomicProperty<Boolean?> = AtomicProperty(null)

  fun isUpgradeAvailable(toolRow: ToolRow): Boolean = toolRow.latestVersion != null

  fun latestVersionFor(toolRow: ToolRow): String? = toolRow.latestVersion

  fun onShown(scope: CoroutineScope) {
    this.scope = scope
    scope.launch {
      refreshUvAvailability()
    }
  }

  fun installTool(toolRow: ToolRow, source: PyToolActionSource) {
    runToolAction(
      toolRow,
      progressTitleKey = "settings.external.tools.install.progress",
      errorTitleKey = "settings.external.tools.install.error.title",
    ) {
      PyToolApi.getInstance().install(toolRow.request())
    } ?: return
    PyToolUsagesCollector.Helper.logToolInstalled(project, toolRow.tool, source)
    toolRow.lastSuccessMessage = toolRow.version?.let {
      PyToolsUiBundle.message("settings.external.tools.install.success.to.version.balloon", toolRow.packageName(), it)
    } ?: PyToolsUiBundle.message("settings.external.tools.install.success.balloon", toolRow.packageName())
  }

  fun upgradeTool(toolRow: ToolRow, source: PyToolActionSource) {
    val previousVersion = toolRow.version
    runToolAction(
      toolRow,
      progressTitleKey = "settings.external.tools.upgrade.progress",
      errorTitleKey = "settings.external.tools.upgrade.error.title",
    ) {
      PyToolApi.getInstance().upgrade(toolRow.request())
    } ?: return
    PyToolUsagesCollector.Helper.logToolUpdated(project, toolRow.tool, source)
    toolRow.lastSuccessMessage = upgradeFeedbackMessage(toolRow, previousVersion, toolRow.version)
  }

  fun installIntoSdkChoosingGroup(
    toolRow: ToolRow,
    sdk: PyToolSdkDto,
    anchor: JComponent,
    source: PyToolActionSource,
  ) {
    val activeScope = scope ?: return
    activeScope.launch {
      val groups = PyToolApi.getInstance().getDependencyGroups(PyToolSdkRequest(toolRow.request(), sdk))
      if (groups.isEmpty()) {
        installIntoSdk(toolRow, sdk, source)
        return@launch
      }
      JBPopupFactory.getInstance()
        .createPopupChooserBuilder(groups)
        .setTitle(PyToolsUiBundle.message("settings.external.tools.install.group.chooser.title"))
        .setRenderer(listCellRenderer {
          @NlsSafe val groupName = value.name
          text(groupName) { align = LcrInitParams.Align.RIGHT }
        })
        .setItemChosenCallback { installIntoSdk(toolRow, sdk, source, it) }
        .createPopup()
        .showUnderneathOf(anchor)
    }
  }

  private fun installIntoSdk(
    toolRow: ToolRow,
    sdk: PyToolSdkDto,
    source: PyToolActionSource,
    dependencyGroup: PyToolDependencyGroupDto? = null,
  ) {
    val title = PyToolsUiBundle.message("settings.external.tools.install.progress", toolRow.packageName())
    val errorTitle = PyToolsUiBundle.message("settings.external.tools.install.error.title", toolRow.packageName())
    val result = runWithModalProgressBlocking(project, title) {
      PyToolApi.getInstance().installIntoSdk(
        PyToolSdkInstallRequest(PyToolSdkRequest(toolRow.request(), sdk), dependencyGroup),
      )
    }
    when (result) {
      is PyToolSdkOperationResultDto.Success -> Unit
      is PyToolSdkOperationResultDto.Failure -> {
        Messages.showErrorDialog(project, result.message, errorTitle)
        return
      }
    }
    PyToolUsagesCollector.Helper.logToolInstalled(project, toolRow.tool, source)
    scope?.launch {
      toolRow.sdkAvailability = SdkAvailability(PyToolApi.getInstance().getSdkStates(toolRow.request()))
      refreshRow(toolRow)
    }
  }

  fun installUv() {
    val title = PyToolsUiBundle.message("settings.external.tools.install.uv.progress")
    val errorTitle = PyToolsUiBundle.message("settings.external.tools.install.uv.error.title")
    val result = runWithModalProgressBlocking(project, title) {
      PyToolApi.getInstance().install(PyToolRequest(project.projectId(), PyToolId("uv")))
    }
    when (result) {
      is PyToolOperationResultDto.Success -> Unit
      is PyToolOperationResultDto.Failure -> {
        Messages.showErrorDialog(project, result.message, errorTitle)
        return
      }
    }
    uvAvailable.set(true)
    onStateChanged()
    scope?.launch { refreshUvAvailability() }
  }

  private fun runToolAction(
    toolRow: ToolRow,
    progressTitleKey: String,
    errorTitleKey: String,
    action: suspend () -> PyToolOperationResultDto,
  ): PyToolStateDto? {
    val title = PyToolsUiBundle.message(progressTitleKey, toolRow.packageName())
    val errorTitle = PyToolsUiBundle.message(errorTitleKey, toolRow.packageName())
    toolRow.actionInProgress = true
    refreshRow(toolRow)
    val result = try {
      runWithModalProgressBlocking(project, title) { action() }
    }
    catch (e: CancellationException) {
      toolRow.actionInProgress = false
      refreshRow(toolRow)
      throw e
    }
    toolRow.actionInProgress = false
    if (!handleResult(toolRow, result, errorTitle)) return null
    refreshRow(toolRow)
    scope?.launch {
      refreshToolState(toolRow)
      refreshUvAvailability()
    }
    return (result as PyToolOperationResultDto.Success).state
  }

  private fun handleResult(toolRow: ToolRow, result: PyToolOperationResultDto, errorTitle: String): Boolean =
    when (result) {
      is PyToolOperationResultDto.Success -> {
        toolRow.applyBackendState(result.state)
        true
      }
      is PyToolOperationResultDto.Failure -> {
        Messages.showErrorDialog(project, result.message, errorTitle)
        false
      }
    }

  private suspend fun refreshUvAvailability() {
    val state = PyToolApi.getInstance().getStates(
      PyToolsRequest(project.projectId(), listOf(PyToolId("uvx"))),
    ).singleOrNull()
    uvAvailable.set(state?.path != null)
    onStateChanged()
  }

  private suspend fun refreshToolState(toolRow: ToolRow) {
    val state = PyToolApi.getInstance().getStates(
      PyToolsRequest(project.projectId(), listOf(toolRow.tool.toolId)),
    ).singleOrNull() ?: return
    toolRow.applyBackendState(state)
    refreshRow(toolRow)
    onStateChanged()
  }

  private fun upgradeFeedbackMessage(toolRow: ToolRow, previous: String?, current: String?): String {
    val name = toolRow.packageName()
    return when {
      current != null && previous == current ->
        PyToolsUiBundle.message("settings.external.tools.upgrade.up.to.date.balloon", name, current)
      current != null ->
        PyToolsUiBundle.message("settings.external.tools.upgrade.success.to.version.balloon", name, current)
      else -> PyToolsUiBundle.message("settings.external.tools.upgrade.success.balloon", name)
    }
  }

  private fun ToolRow.request(): PyToolRequest = PyToolRequest(project.projectId(), tool.toolId)

  private fun ToolRow.packageName(): String = tool.packageName.name
}
