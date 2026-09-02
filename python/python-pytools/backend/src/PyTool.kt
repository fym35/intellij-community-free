// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.pytools.backend

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.python.pytools.backend.statistics.PyToolFusSnapshot
import com.intellij.python.pytools.common.PyToolConfigurationDto
import com.jetbrains.python.packaging.PyPackageName

interface PyTool : PyExecutable {
  val packageName: PyPackageName

  override val fusId: String get() = packageName.name
  val executables: List<PyExecutable> get() = listOf(this)
  val manager: PyToolManager? get() = PackagePyToolManager

  fun migrateLegacyState(project: Project): PyToolsState.ToolEntry? = null

  fun onEnabledChanged(project: Project, enabled: Boolean) {}

  fun isSelectedAsTypeEngine(project: Project): Boolean = false

  fun configurationState(project: Project): PyToolConfigurationDto? = null

  fun applyConfigurationState(project: Project, state: PyToolConfigurationDto) {}

  fun configurationFusSnapshot(project: Project): PyToolFusSnapshot = PyToolFusSnapshot(
    enabled = PyToolsState.getInstance(project).isEnabled(this),
    customPath = getCustomExecutablePath(project.getEelDescriptor()) != null,
  )

  companion object {
    val EP_NAME: ExtensionPointName<PyTool> = ExtensionPointName.create("com.intellij.python.pytools.pyTool")

    fun findByPackageName(packageName: String): PyTool? {
      val normalized = PyPackageName.from(packageName).name
      return EP_NAME.extensionList.firstOrNull { it.packageName.name == normalized }
    }

    fun findExecutable(name: String): PyExecutable? =
      EP_NAME.extensionList.firstNotNullOfOrNull { tool -> tool.executables.firstOrNull { it.fusId == name } }
  }
}

/** A backend tool whose executable participates in package-manager detection. */
interface PackageManagerPyTool : PyTool

fun PyTool.isEnabledOn(project: Project): Boolean = PyToolsState.getInstance(project).isEnabled(this)

fun PyTool.isActiveOn(project: Project): Boolean = isEnabledOn(project) || isSelectedAsTypeEngine(project)
