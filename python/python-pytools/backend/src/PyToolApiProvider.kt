// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.pytools.backend

import com.intellij.openapi.project.Project
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.platform.project.findProject
import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.backend.RemoteApiProvider
import com.intellij.python.pytools.common.PyToolApi
import com.intellij.python.pytools.common.PyToolEnabledStateDto
import com.intellij.python.pytools.common.PyToolEventKind
import com.intellij.python.pytools.common.PyToolId
import com.intellij.python.pytools.common.PyToolLogEventRequest
import com.intellij.python.pytools.common.PyToolSetEnabledRequest
import com.intellij.python.pytools.common.PyToolSetConfigurationRequest
import com.intellij.python.pytools.common.PyToolOperationResultDto
import com.intellij.python.pytools.common.PyToolPathKind
import com.intellij.python.pytools.common.PyToolPathRequest
import com.intellij.python.pytools.common.PyToolRequest
import com.intellij.python.pytools.common.PyToolSdkInstallRequest
import com.intellij.python.pytools.common.PyToolSdkOperationResultDto
import com.intellij.python.pytools.common.PyToolSdkRequest
import com.intellij.python.pytools.common.PyToolSdkStateDto
import com.intellij.python.pytools.common.PyToolSetPathRequest
import com.intellij.python.pytools.common.PyToolStateDto
import com.intellij.python.pytools.common.PyToolValidationDto
import com.intellij.python.pytools.common.PyToolsRequest
import com.intellij.python.pytools.backend.statistics.PyToolUsagesCollector
import com.intellij.python.requirements.PyPackageVersionComparator
import com.jetbrains.python.Result
import com.jetbrains.python.errorProcessing.PyResult
import fleet.rpc.remoteApiDescriptor
import java.nio.file.Path

internal class PyToolApiProvider : RemoteApiProvider {
  override fun RemoteApiProvider.Sink.remoteApis() {
    remoteApi(remoteApiDescriptor<PyToolApi>()) { PyToolApiImpl }
  }
}

private object PyToolApiImpl : PyToolApi {
  override suspend fun isStateInitialized(projectId: ProjectId): Boolean =
    PyToolsState.getInstance(projectId.findProject()).isInitialized()

  override suspend fun initializeState(projectId: ProjectId) {
    val project = projectId.findProject()
    val entries = PyTool.EP_NAME.extensionList.mapNotNull { tool ->
      tool.migrateLegacyState(project)?.let { PyToolEnabledStateDto(PyToolId(tool.packageName.name), it.enabled) }
    }
    PyToolsState.getInstance(project).initialize(entries)
  }

  override suspend fun observeEnabledStates(projectId: ProjectId) =
    PyToolsState.getInstance(projectId.findProject()).enabledStates()

  override suspend fun getStates(request: PyToolsRequest): List<PyToolStateDto> {
    val project = request.projectId.findProject()
    val eel = project.getEelDescriptor().toEelApi()
    val installed = GenericPyToolManagerProvider.managerFor(eel)?.list().orEmpty().mapKeys { (tool, _) -> tool.fusId }
    return request.toolIds.mapNotNull { id ->
      PyTool.findExecutable(id.value)?.let { executable ->
        val info = installed[executable.fusId]
        state(
          project,
          executable,
          latestVersion = info?.latestVersion?.takeIf {
            PyPackageVersionComparator.STR_COMPARATOR.compare(it, info.installedVersion) > 0
          },
        )
      }
    }
  }

  override suspend fun validatePath(request: PyToolPathRequest): PyToolValidationDto {
    request.tool.projectId.findProject()
    return when (val result = requireTool(request.tool).validateCustomPath(Path.of(request.path))) {
      is Result.Success -> PyToolValidationDto.Valid(result.result.value)
      is Result.Failure -> PyToolValidationDto.Invalid(result.error.toString())
    }
  }

  override suspend fun setPath(request: PyToolSetPathRequest): PyToolStateDto {
    val project = request.tool.projectId.findProject()
    val tool = requireTool(request.tool)
    tool.setCustomExecutablePath(project.getEelDescriptor(), request.path?.let(Path::of))
    return state(project, tool)
  }

  override suspend fun setEnabled(request: PyToolSetEnabledRequest): PyToolStateDto {
    val project = request.tool.projectId.findProject()
    val tool = requireTool(request.tool)
    PyToolsState.getInstance(project).setEnabled(request.tool.toolId, request.enabled)
    tool.onEnabledChanged(project, request.enabled)
    return state(project, tool)
  }

  override suspend fun setConfiguration(request: PyToolSetConfigurationRequest): PyToolStateDto {
    val project = request.tool.projectId.findProject()
    val tool = requireTool(request.tool)
    tool.applyConfigurationState(project, request.configuration)
    return state(project, tool)
  }

  override suspend fun install(request: PyToolRequest): PyToolOperationResultDto =
    operate(request) { project, tool -> tool.performToolInstallation(project.getEelDescriptor().toEelApi()) }

  override suspend fun upgrade(request: PyToolRequest): PyToolOperationResultDto =
    operate(request) { project, tool -> tool.performToolUpgrade(project.getEelDescriptor().toEelApi()) }

  override suspend fun getSdkStates(request: PyToolRequest): List<PyToolSdkStateDto> {
    val project = request.projectId.findProject()
    return PyToolSdkBackendService.getInstance().getStates(project, requireTool(request))
  }

  override suspend fun getDependencyGroups(request: PyToolSdkRequest) =
    PyToolSdkBackendService.getInstance().getDependencyGroups(request.tool.projectId.findProject(), request)

  override suspend fun installIntoSdk(request: PyToolSdkInstallRequest): PyToolSdkOperationResultDto {
    val project = request.target.tool.projectId.findProject()
    return PyToolSdkBackendService.getInstance().install(project, requireTool(request.target.tool), request)
  }

  override suspend fun logEvent(request: PyToolLogEventRequest) {
    val project = request.tool.projectId.findProject()
    val tool = requireTool(request.tool)
    when (request.event) {
      PyToolEventKind.CONFIGURATION_CHANGED -> PyToolUsagesCollector.Helper.logConfigurationChanged(project, tool, request.source)
      PyToolEventKind.INSTALLED -> PyToolUsagesCollector.Helper.logToolInstalled(project, tool, request.source)
      PyToolEventKind.UPDATED -> PyToolUsagesCollector.Helper.logToolUpdated(project, tool, request.source)
    }
  }

  private suspend fun operate(
    request: PyToolRequest,
    operation: suspend (Project, PyTool) -> PyResult<Path>,
  ): PyToolOperationResultDto {
    val project = request.projectId.findProject()
    val tool = requireTool(request)
    return when (val result = operation(project, tool)) {
      is Result.Success -> PyToolOperationResultDto.Success(state(project, tool, knownPath = result.result))
      is Result.Failure -> PyToolOperationResultDto.Failure(result.error.toString())
    }
  }

  private suspend fun state(
    project: Project,
    executable: PyExecutable,
    knownPath: Path? = null,
    latestVersion: String? = null,
  ): PyToolStateDto {
    val descriptor = project.getEelDescriptor()
    val custom = executable.getCustomExecutablePath(descriptor)
    val path = custom ?: knownPath ?: PyExecutableCache.getInstance().get(descriptor, executable)
    val tool = executable as? PyTool
    val version = if (tool != null && path != null) {
      (tool.validateCustomPath(path) as? Result.Success)?.result?.value
    }
    else null
    return PyToolStateDto(
      toolId = PyToolId(executable.fusId),
      enabled = PyToolsState.getInstance(project).isEnabled(PyToolId(executable.fusId)),
      path = path?.toString(),
      pathKind = when {
        custom != null -> PyToolPathKind.CUSTOM
        path != null -> PyToolPathKind.DETECTED
        else -> null
      },
      version = version,
      canInstall = tool?.manager?.canInstall(descriptor) == true,
      latestVersion = latestVersion,
      configuration = tool?.configurationState(project),
      selectedAsTypeEngine = tool?.isSelectedAsTypeEngine(project) == true,
    )
  }

  private fun requireTool(request: PyToolRequest): PyTool =
    PyTool.findByPackageName(request.toolId.value) ?: error("Unknown Python tool: " + request.toolId.value)
}
