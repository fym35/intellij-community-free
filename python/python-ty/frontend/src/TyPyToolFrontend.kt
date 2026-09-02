// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.ty.frontend

import com.intellij.python.pytools.frontend.LspPyToolFrontend
import com.intellij.python.pytools.frontend.icons.PythonPytoolsFrontendIcons
import com.intellij.util.IconUtil
import javax.swing.Icon

internal class TyPyToolFrontend : LspPyToolFrontend {
  override val presentableName: String = "ty"
  override val description: String get() = TyFrontendBundle.message("ty.tool.description")
  override val packageName: String = "ty"
  override val icon: Icon = IconUtil.downscaleIconToSize(PythonPytoolsFrontendIcons.Expui.TY, 16, 16)
}
