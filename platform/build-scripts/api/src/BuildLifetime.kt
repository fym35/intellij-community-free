// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import com.intellij.platform.buildScripts.concurrency.SharedTaskOwner
import org.jetbrains.annotations.ApiStatus

/** Owns shared tasks and resources for a build. Close it after all build scopes have closed. */
@ApiStatus.Internal
class BuildLifetime : AutoCloseable {
  val sharedTasks: SharedTaskOwner = SharedTaskOwner("build")

  /** Closes every registered resource, the last one first. The first failure is thrown with the others suppressed. */
  override fun close() {
    sharedTasks.close()
  }
}
