// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.pyright.frontend

import com.intellij.python.pytools.frontend.LspPyToolFrontend
import com.intellij.python.pytools.frontend.icons.PythonPytoolsFrontendIcons
import com.intellij.util.IconUtil
import com.jetbrains.python.packaging.PyPackageName
import javax.swing.Icon

internal class BasedpyrightPyToolFrontend : LspPyToolFrontend {
  override val presentableName: String = "Basedpyright"
  override val description: String get() = PyrightFrontendBundle.message("basedpyright.tool.description")
  override val packageName: PyPackageName = PyPackageName.from("basedpyright")
  override val icon: Icon = IconUtil.resizeSquared(PythonPytoolsFrontendIcons.Expui.Basedpyright, 16)
}
