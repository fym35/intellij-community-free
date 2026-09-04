// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build.impl

import com.intellij.util.JavaModuleOptions
import com.intellij.util.system.OS
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.context.Context
import org.jetbrains.intellij.build.BuildContext
import org.jetbrains.intellij.build.CompilationContext
import org.jetbrains.intellij.build.OsFamily
import org.jetbrains.intellij.build.Subtask
import org.jetbrains.intellij.build.TaskScope
import org.jetbrains.intellij.build.executeStep
import org.jetbrains.intellij.build.io.copyDir
import org.jetbrains.intellij.build.productLayout.util.AsyncCache
import org.jetbrains.intellij.build.telemetry.TraceManager.spanBuilder
import org.jetbrains.intellij.build.telemetry.use
import java.nio.file.Path
import java.util.function.Predicate

/** Forks a build step into the group. The subtask holds `null` when the step is skipped or fails. */
fun TaskScope.createSkippableJob(
  spanBuilder: SpanBuilder,
  stepId: String,
  context: BuildContext,
  task: () -> Unit,
): Subtask<Unit?> {
  return fork("$stepId build step") {
    context.executeStep(spanBuilder, stepId) {
      task()
    }
  }
}

/**
 * Filter is applied only to files, not to directories.
 *
 * Returns the files that were written; see [copyDir].
 */
fun copyDirWithFileFilter(fromDir: Path, targetDir: Path, fileFilter: Predicate<Path>): List<Path> {
  return copyDir(sourceDir = fromDir, targetDir = targetDir, fileFilter = fileFilter)
}

fun zip(targetFile: Path, dir: Path, context: CompilationContext) {
  spanBuilder("pack")
    .setAttribute("targetFile", context.paths.buildOutputDir.relativize(targetFile).toString())
    .use {
      org.jetbrains.intellij.build.io.zipWithPackageIndex(targetFile = targetFile, dir = dir)
    }
}

/**
 * @return a list of JVM args for opened packages (JBR17+) in a format `--add-opens=PACKAGE=ALL-UNNAMED` for a specified or current OS
 */
internal fun getCommandLineArgumentsForOpenPackages(context: CompilationContext, target: OsFamily? = null): List<String> {
  val file = context.paths.communityHomeDir.resolve("platform/platform-impl/resources/META-INF/OpenedPackages.txt")
  val os = when (target) {
    OsFamily.WINDOWS -> OS.Windows
    OsFamily.MACOS -> OS.macOS
    OsFamily.LINUX -> OS.Linux
    null -> OS.CURRENT
  }
  return JavaModuleOptions.readOptions(file, os)
}

/**
 * A value that the first caller computes and every caller shares.
 */
interface SharedLazy<T> {
  /** Returns the value, and blocks the calling virtual thread while the first caller computes it. */
  fun get(): T
}

/**
 * Computes a value on the first [SharedLazy.get] and shares the result with all concurrent callers.
 *
 * The initializer runs on a virtual thread of its own, under the telemetry context of the caller that started it.
 * A caller blocks only its own virtual thread. A recursive `get` from inside the initializer fails fast. Successful
 * values and ordinary failures are reused. [name] names the computation.
 */
fun <T> sharedLazy(name: String, initializer: () -> T): SharedLazy<T> {
  return AsyncCacheBackedSharedLazy(name = name, initializer = initializer)
}

private class AsyncCacheBackedSharedLazy<T>(
  name: String,
  private val initializer: () -> T,
) : SharedLazy<T> {
  private val key = NamedLazyKey(name)

  /**
   * The cache gives the single flight, the reuse of a failure, and the fail-fast on a recursive call. The key carries
   * the name of the lazy, so the computation also names its thread.
   */
  private val cache = AsyncCache<NamedLazyKey, T>()

  override fun get(): T {
    // the computation runs on a thread of its own, so a span of the initializer gets its parent from here
    val telemetryContext = Context.current()
    return cache.getOrPut(key) { telemetryContext.makeCurrent().use { initializer() } }
  }
}

private class NamedLazyKey(private val name: String) {
  override fun toString(): String = name
}
