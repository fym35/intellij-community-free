// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.black

import com.intellij.codeInsight.actions.onSave.FormatOnSaveOptions
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Version
import com.intellij.openapi.util.registry.Registry
import com.intellij.python.pytools.backend.PyTool
import com.intellij.python.pytools.frontend.ExternalPyToolFrontend as ExternalPyTool
import com.intellij.python.pytools.frontend.PyToolFrontend
import com.intellij.python.black.PyBlackBundle.message
import com.intellij.python.black.configuration.BlackFormatterConfigurable
import com.intellij.python.black.configuration.BlackFormatterConfiguration
import com.intellij.python.black.icons.PythonBlackIcons
import com.jetbrains.python.packaging.PyPackageName
import org.jetbrains.annotations.ApiStatus
import javax.swing.Icon

/**
 * [Black](https://black.readthedocs.io/) — the uncompromising Python code formatter maintained under
 * the PSF. It reformats source into a single, consistent style, leaving little to configure.
 */
@ApiStatus.Internal
class BlackPyTool : PyTool {
  override val packageName: PyPackageName = PyPackageName.from("black")

  companion object {
    fun getInstance(): BlackPyTool = PyTool.EP_NAME.findExtensionOrFail(BlackPyTool::class.java)
  }
}

@ApiStatus.Internal
class BlackPyToolFrontend : ExternalPyTool {
  override val presentableName: String = "Black"
  override val description: String get() = message("black.tool.description")
  override val packageName: PyPackageName = PyPackageName.from("black")
  override val icon: Icon get() = PythonBlackIcons.Black

  /**
   * `--line-ranges` (fragment formatting) requires Black 23.11.0; older versions cannot honour
   * range-restricted formatting requests from the IDE.
   */
  override val minimumSupportedVersion: Version = Version(23, 11, 0)

  @Suppress("DEPRECATION")
  override fun migrateLegacyState(project: Project): Boolean {
    val cfg = BlackFormatterConfiguration.getBlackConfiguration(project)
    val enabled = Registry.`is`("black.formatter.support.enabled") && cfg.enabledOnReformat
    cfg.enabledOnReformat = false
    cfg.executionMode = BlackFormatterConfiguration.ExecutionMode.PACKAGE
    cfg.pathToExecutable = null
    return enabled
  }

  override fun createConfigurable(project: Project): UnnamedConfigurable = BlackFormatterConfigurable(project)

  override fun summaryFor(project: Project): String {
    val cfg = BlackFormatterConfiguration.getBlackConfiguration(project)
    return buildList {
      add(message("black.enable.black.checkbox.label"))
      if (FormatOnSaveOptions.getInstance(project).isRunOnSaveEnabled) {
        add(message("black.enable.action.on.save.label"))
      }
      cfg.cmdArguments.trim().takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(", ")
  }

  @Suppress("CompanionObjectInExtension")
  companion object {
    fun getInstance(): BlackPyToolFrontend = PyToolFrontend.EP_NAME.findExtensionOrFail(BlackPyToolFrontend::class.java)
  }
}
