// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import org.jetbrains.annotations.ApiStatus

/**
 * The owner of the resources a build context opens, such as the cached module output archives.
 *
 * It replaces the coroutine scope whose completion released them. A root opens one, hands it to the context it
 * creates, and closes it when the build ends. `null` in place of a lifetime means that nothing is cached.
 */
@ApiStatus.Internal
class BuildLifetime : AutoCloseable {
  private val closeables = ArrayDeque<AutoCloseable>()
  private var closed = false

  /** Registers [closeable]. When the lifetime is closed already, [closeable] is closed at once. */
  fun onClose(closeable: AutoCloseable) {
    val closeNow = synchronized(closeables) {
      if (!closed) {
        closeables.addFirst(closeable)
      }
      closed
    }
    if (closeNow) {
      closeable.close()
    }
  }

  /** Closes every registered resource, the last one first. The first failure is thrown with the others suppressed. */
  override fun close() {
    val toClose = synchronized(closeables) {
      closed = true
      val copy = closeables.toList()
      closeables.clear()
      copy
    }
    var failure: Throwable? = null
    for (closeable in toClose) {
      try {
        closeable.close()
      }
      catch (e: Throwable) {
        if (failure == null) {
          failure = e
        }
        else {
          failure.addSuppressed(e)
        }
      }
    }
    if (failure != null) {
      throw failure
    }
  }
}
