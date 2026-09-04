// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import org.jetbrains.annotations.ApiStatus.Internal

/**
 * The single-flight computations the current thread runs inside.
 *
 * A thread local, because a shared computation starts on a thread of its own. A fork copies the set of its caller,
 * see [TaskScope.fork].
 */
private val activeSingleFlightOwners: ThreadLocal<Set<Any>> = ThreadLocal.withInitial { emptySet() }

/** The owners to hand to a computation that the current thread starts on a thread of its own. */
@Internal
fun currentSingleFlightOwners(): Set<Any> = activeSingleFlightOwners.get()

/**
 * Runs [body] inside the computations of [inherited], and of [owner] when it is not `null`.
 *
 * A shared computation calls this on its own thread with the owners its caller had.
 */
@Internal
fun <T> withSingleFlightOwners(inherited: Set<Any>, owner: Any?, body: () -> T): T {
  val previous = activeSingleFlightOwners.get()
  activeSingleFlightOwners.set(if (owner == null) inherited else inherited + owner)
  try {
    return body()
  }
  finally {
    activeSingleFlightOwners.set(previous)
  }
}

/** Fails when the caller runs inside the computation of [owner] and that computation is not [completed] yet. */
@Internal
fun checkRecursiveSingleFlightAwait(owner: Any, operationName: String, completed: Boolean) {
  check(completed || owner !in activeSingleFlightOwners.get()) {
    "Recursive await of '$operationName' detected"
  }
}
