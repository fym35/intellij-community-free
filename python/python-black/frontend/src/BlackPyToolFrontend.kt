// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.black.frontend

import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Version
import com.intellij.python.pytools.common.PyBlackToolConfigurationDto
import com.intellij.python.pytools.common.PyToolConfigurationDto
import com.intellij.python.pytools.frontend.ExternalPyToolFrontend
import com.intellij.python.pytools.frontend.icons.PythonPytoolsFrontendIcons
import com.intellij.python.black.frontend.PyBlackFrontendBundle.message
import com.jetbrains.python.packaging.PyPackageName
import org.jetbrains.annotations.ApiStatus
import javax.swing.Icon

@ApiStatus.Internal
internal class BlackPyToolFrontend : ExternalPyToolFrontend {
  override val presentableName: String = "Black"
  override val description: String get() = message("black.tool.description")
  override val packageName: PyPackageName = PyPackageName.from("black")
  override val icon: Icon get() = PythonPytoolsFrontendIcons.Expui.Black
  override val minimumSupportedVersion: Version = Version(23, 11, 0)

  override fun createConfigurable(project: Project): UnnamedConfigurable = BlackFormatterConfigurable(project)

  override fun summary(project: Project, configuration: PyToolConfigurationDto?): String {
    val arguments = (configuration as? PyBlackToolConfigurationDto)?.arguments ?: return ""
    return buildList {
      add(message("black.enable.black.checkbox.label"))
      if (FormatOnSaveOptions.getInstance(project).isRunOnSaveEnabled) add(message("black.enable.action.on.save.label"))
      arguments.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(", ")
  }
}
