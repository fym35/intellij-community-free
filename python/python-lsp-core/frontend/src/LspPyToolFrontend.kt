// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.lsp.core.frontend

import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.project.Project
import com.intellij.python.lsp.core.common.PyLspToolConfigurationDto
import com.intellij.python.pytools.frontend.ExternalPyToolFrontend
import org.jetbrains.annotations.Nls

interface LspPyToolFrontend : ExternalPyToolFrontend<PyLspToolConfigurationDto> {
  override val configurationClass: Class<PyLspToolConfigurationDto>
    get() = PyLspToolConfigurationDto::class.java

  override fun createConfigurable(project: Project): UnnamedConfigurable = createLspToolConfigurable(project, this)

  override fun summary(project: Project, configuration: PyLspToolConfigurationDto): String =
    pyLspToolFeaturesSummary(configuration, this)

  val formattingLabel: @Nls String? get() = null
  val sortImportsLabel: @Nls String? get() = null
}
