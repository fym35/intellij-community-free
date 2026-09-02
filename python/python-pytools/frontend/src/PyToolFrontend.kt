// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.pytools.frontend

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.Version
import com.intellij.python.pytools.common.PyToolId
import com.intellij.python.pytools.frontend.statistics.PyToolFusSnapshot
import com.intellij.python.pytools.frontend.ui.PyToolsUiBundle
import com.jetbrains.python.packaging.PyPackageName
import org.jetbrains.annotations.Nls
import javax.swing.Icon

interface PyToolFrontend {
  val presentableName: @NlsSafe String
  val packageName: PyPackageName
  val icon: Icon
  val description: @Nls String
  val minimumSupportedVersion: Version? get() = null

  val toolId: PyToolId get() = PyToolId(packageName.name)
  val fusId: String get() = packageName.name

  fun migrateLegacyState(project: Project): Boolean? = null
  fun summaryFor(project: Project): @NlsSafe String = ""
  fun onEnabledChanged(project: Project, enabled: Boolean) {}
  fun isSelectedAsTypeEngine(project: Project): Boolean = false

  fun configurationFusSnapshot(project: Project): PyToolFusSnapshot {
    return PyToolFusSnapshot(enabled = PyToolsFrontendState.getInstance(project).isEnabled(toolId), customPath = false)
  }

  companion object {
    val EP_NAME: ExtensionPointName<PyToolFrontend> = ExtensionPointName.create("com.intellij.python.pytools.pyToolFrontend")

    fun findByPackageName(packageName: String): PyToolFrontend? {
      val normalized = PyPackageName.from(packageName).name
      return EP_NAME.extensionList.firstOrNull { it.packageName.name == normalized }
    }
  }
}

interface ExternalPyToolFrontend : PyToolFrontend {
  fun createConfigurable(project: Project): UnnamedConfigurable

  fun enableToggleConfirmation(isOn: Boolean, isTypeEngine: Boolean): @Nls String? {
    if (isOn || !isTypeEngine) return null
    return PyToolsUiBundle.message("py.tool.toggle.confirm.type.engine", presentableName)
  }
}

interface PackageManagerPyToolFrontend : PyToolFrontend

fun PyToolFrontend.isEnabledOn(project: Project): Boolean = PyToolsFrontendState.getInstance(project).isEnabled(toolId)

fun PyToolFrontend.isActiveOn(project: Project): Boolean = isEnabledOn(project) || isSelectedAsTypeEngine(project)
