// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.pytools.frontend

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.util.Version
import com.intellij.python.pytools.common.PyToolId
import com.intellij.python.pytools.common.PyToolConfigurationDto
import com.intellij.python.pytools.frontend.ui.PyToolsUiBundle
import org.jetbrains.annotations.Nls
import javax.swing.Icon

interface PyToolFrontend {
  val presentableName: @NlsSafe String
  val packageName: @NlsSafe String
  val icon: Icon
  val description: @Nls String
  val minimumSupportedVersion: Version? get() = null

  val toolId: PyToolId get() = PyToolId(packageName)

  companion object {
    val EP_NAME: ExtensionPointName<PyToolFrontend> = ExtensionPointName.create("com.intellij.python.pytools.pyToolFrontend")

    fun findByPackageName(packageName: String): PyToolFrontend? {
      val normalized = normalizePackageName(packageName)
      return EP_NAME.extensionList.firstOrNull { it.packageName == normalized }
    }
  }
}

private fun normalizePackageName(packageName: String): String {
  var name = packageName.trim()
    .removePrefix("\"")
    .removeSuffix("\"")

  if (!name.startsWith("_")) {
    name = name.replace('_', '-')
  }

  return name.replace('.', '-').lowercase()
}

interface ExternalPyToolFrontend<C : PyToolConfigurationDto> : PyToolFrontend {
  val configurationClass: Class<C>

  fun createConfigurable(project: Project): UnnamedConfigurable

  fun summary(project: Project, configuration: C): @NlsSafe String = ""

  fun enableToggleConfirmation(isOn: Boolean, isTypeEngine: Boolean): @Nls String? {
    if (isOn || !isTypeEngine) return null
    return PyToolsUiBundle.message("py.tool.toggle.confirm.type.engine", presentableName)
  }
}

interface PackageManagerPyToolFrontend : PyToolFrontend
