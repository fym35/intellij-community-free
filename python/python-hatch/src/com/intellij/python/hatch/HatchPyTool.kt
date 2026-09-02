// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.hatch

import com.intellij.python.hatch.PyHatchBundle.message
import com.intellij.python.hatch.icons.PythonHatchIcons
import com.intellij.python.pytools.backend.PackageManagerPyTool
import com.intellij.python.pytools.backend.PyTool
import com.intellij.python.pytools.frontend.PackageManagerPyToolFrontend
import com.intellij.python.pytools.frontend.PyToolFrontend
import com.jetbrains.python.packaging.PyPackageName
import org.jetbrains.annotations.ApiStatus
import javax.swing.Icon

/**
 * [Hatch](https://hatch.pypa.io/) — a modern, extensible Python project manager maintained under the
 * PyPA. It manages isolated and matrix environments, builds distributions through its Hatchling build
 * backend, bumps and manages project versions, publishes packages to PyPI, and runs project scripts.
 */
@ApiStatus.Internal
class HatchPyTool : PackageManagerPyTool {
  override val packageName: PyPackageName = PyPackageName.from("hatch")

  @Suppress("CompanionObjectInExtension")
  companion object {
    fun getInstance(): HatchPyTool = PyTool.EP_NAME.findExtensionOrFail(HatchPyTool::class.java)
  }
}

@ApiStatus.Internal
class HatchPyToolFrontend : PackageManagerPyToolFrontend {
  override val presentableName: String = "Hatch"
  override val packageName: PyPackageName = PyPackageName.from("hatch")
  override val description: String get() = message("python.hatch.tool.description")
  override val icon: Icon get() = PythonHatchIcons.Logo

  companion object {
    fun getInstance(): HatchPyToolFrontend = PyToolFrontend.EP_NAME.findExtensionOrFail(HatchPyToolFrontend::class.java)
  }
}
