// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.devDist

import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.intellij.build.ModuleOutputProvider
import org.jetbrains.intellij.build.impl.BazelModuleOutputProvider
import org.jetbrains.intellij.build.impl.JpsModuleOutputProvider
import org.jetbrains.intellij.build.impl.bazelOutputRoot
import org.jetbrains.jps.model.JpsProject
import java.nio.file.Path

/**
 * A module output provider over a loaded [project], for a reader of the derivation that has no `CompilationContext`.
 *
 * The derivation itself reads the project model alone. The product discovery in front of it loads every
 * `ProductProperties` class from compiled build modules, and this provider names those outputs. A process that runs
 * from a Bazel output tree reads the outputs `bazel-targets.json` names. Every other process reads the JPS output
 * directories of the project.
 *
 * [scope] owns the cache of opened module output archives, or `null` for a provider without a cache.
 */
@ApiStatus.Internal
fun createDevDistModuleOutputProvider(project: JpsProject, projectHome: Path, scope: CoroutineScope?): ModuleOutputProvider {
  val bazelOutputRoot = bazelOutputRoot ?: return JpsModuleOutputProvider(project = project, useTestCompilationOutput = true)
  return BazelModuleOutputProvider(
    modules = project.modules,
    projectHome = projectHome,
    bazelOutputRoot = bazelOutputRoot,
    scope = scope,
    useTestCompilationOutput = true,
  )
}
