// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import io.opentelemetry.context.Context
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException

/**
 * The body of a virtual thread. It drops its task before it runs the task.
 *
 * A dead virtual thread keeps its task through its continuation. A span, a debug probe or a logger can hold the
 * thread. Such a reference would keep the whole task and everything it captured alive.
 */
private class ForgetfulTask(task: Runnable) : Runnable {
  private var task: Runnable? = task

  override fun run() {
    val task = task ?: return
    this.task = null
    task.run()
  }
}

/**
 * What a group of forks does when one of them fails.
 */
@ApiStatus.Internal
enum class TaskScopePolicy {
  /** The first failure cancels the other forks. */
  FAIL_FAST,

  /** Every fork runs to its end. Then the first failure is thrown with the others suppressed. */
  RUN_ALL,
}

/**
 * Starts [block] on a virtual thread of its own, and returns the future of its result.
 *
 * This is the primitive of a shared computation, such as an entry of `AsyncCache`, that outlives its first caller.
 * The computation is not a child of that caller: once it starts it runs to its end, and every caller only waits with
 * [joinShared]. A fan-out inside the pipeline uses [taskScope] instead.
 *
 * The body runs inside the single-flight computations of the caller, so a recursive wait for the same key fails fast.
 * It carries no telemetry context. A caller whose computation opens a span makes the context current in the entry it runs.
 */
@ApiStatus.Internal
fun <T> startSharedComputation(name: String, owner: Any?, block: () -> T): CompletableFuture<T> {
  val future = CompletableFuture<T>()
  val inherited = currentSingleFlightOwners()
  val body = Runnable {
    try {
      future.complete(withSingleFlightOwners(inherited = inherited, owner = owner, body = block))
    }
    catch (e: Throwable) {
      future.completeExceptionally(e)
    }
  }
  Thread.ofVirtual().name(name).start(ForgetfulTask(body))
  return future
}

/**
 * Waits for a future that other waiters share, and throws its failure as the computation threw it.
 *
 * The wait is interruptible: an interrupt of the calling thread is the cancel of the caller, and it changes nothing
 * for the computation or for the other waiters. `get` needs no defensive copy, because it cancels nothing.
 */
@ApiStatus.Internal
fun <T> CompletableFuture<T>.joinShared(): T {
  try {
    return get()
  }
  catch (e: ExecutionException) {
    throw e.cause ?: e
  }
}

/** The exception of a future that completed exceptionally, without the `CompletionException` wrapper of `join`. */
@ApiStatus.Internal
fun CompletableFuture<*>.failureOrNull(): Throwable? {
  if (!isCompletedExceptionally) {
    return null
  }
  val e = runCatching { join() }.exceptionOrNull() ?: return null
  return (e as? CompletionException)?.cause ?: e
}

/**
 * The handle of a fork, as `StructuredTaskScope.Subtask` is in the JDK.
 *
 * It only hands out the result. Only the group cancels a fork: a joiner that is interrupted stops waiting and
 * changes nothing for the fork or for the other joiners.
 */
@ApiStatus.Internal
class Subtask<T> internal constructor(private val future: CompletableFuture<T>) {
  /**
   * Blocks until the fork ends, and returns its result or throws its failure.
   *
   * A fork that the group cancelled throws a [CancellationException]. The wait is interruptible, so a cancel of the
   * caller reaches a block that waits for a fork.
   */
  fun join(): T = future.joinShared()

  companion object {
    /** A handle that holds [value] already, for a step of a graph that has nothing to compute. */
    fun <T> completed(value: T): Subtask<T> = Subtask(CompletableFuture.completedFuture(value))
  }
}

/**
 * A group of forks that [taskScope] joins when its block ends.
 *
 * It mirrors `java.util.concurrent.StructuredTaskScope`. A fork is a virtual thread of its own, and the group holds
 * no queue, so a scheduler cannot lose a fork.
 */
@ApiStatus.Internal
class TaskScope internal constructor(private val policy: TaskScopePolicy) {
  private val forks = CopyOnWriteArrayList<Fork<*>>()

  /**
   * The failures the forks reported before the group cancelled, in the order the forks ended. A report after the cancel
   * is ignored, as `StructuredTaskScope` ignores it: an interrupt, a [CancellationException] or any other exception.
   */
  private val failures = CopyOnWriteArrayList<Throwable>()

  @Volatile
  private var cancelRequested = false

  @Volatile
  private var closed = false

  /**
   * Starts [block] on a virtual thread named [name].
   *
   * The fork sees the telemetry context and the single-flight computations of the calling thread. A fork started after
   * the group cancelled is not started, as in `StructuredTaskScope`, and its [Subtask.join] throws the cancellation.
   */
  fun <T> fork(name: String, block: () -> T): Subtask<T> {
    check(!closed) { "The group of forks has ended, so it cannot start '$name'" }
    val fork = Fork<T>()
    val telemetryContext = Context.current()
    val singleFlightOwners = currentSingleFlightOwners()
    val body = Runnable {
      if (cancelRequested) {
        // the cancel came between the registration and the start, so the body must not run
        fork.end(this, null, TaskScopeCancellationException())
        return@Runnable
      }
      try {
        val result = telemetryContext.makeCurrent().use {
          withSingleFlightOwners(inherited = singleFlightOwners, owner = null, body = block)
        }
        fork.end(this, result, null)
      }
      catch (e: Throwable) {
        fork.end(this, null, e)
      }
    }
    fork.thread = Thread.ofVirtual().name(name).unstarted(ForgetfulTask(body))
    forks.add(fork)
    if (cancelRequested) {
      // a fork that a cancelled fork starts must not outlive the cancel
      fork.future.completeExceptionally(TaskScopeCancellationException())
    }
    else {
      fork.thread.start()
    }
    return Subtask(fork.future)
  }

  /** Records what a fork reported. After the cancel the report is ignored and the fork is cancelled. */
  private fun <T> record(fork: Fork<T>, result: T?, e: Throwable?) {
    if (cancelRequested) {
      fork.future.completeExceptionally(TaskScopeCancellationException())
      return
    }
    if (e == null) {
      @Suppress("UNCHECKED_CAST")
      fork.future.complete(result as T)
      return
    }
    failures.add(e)
    fork.future.completeExceptionally(e)
    if (policy == TaskScopePolicy.FAIL_FAST) {
      cancelAll()
    }
  }

  /** Interrupts every running fork, and lets no other fork start. */
  internal fun cancelAll() {
    cancelRequested = true
    for (fork in forks) {
      fork.thread.interrupt()
    }
  }

  /**
   * Waits for every fork to end, including a fork that another fork adds meanwhile, and closes the group.
   *
   * A cancelled fork has ended only when its body has returned, so no fork works on after the group. Throws the
   * first failure with the other failures suppressed. When the calling thread is interrupted while it waits, the
   * group cancels the forks, waits for them without interruption, re-asserts the interrupt, and throws.
   */
  internal fun joinAll() {
    try {
      try {
        joinPending()
      }
      catch (e: InterruptedException) {
        cancelAll()
        joinPendingUninterruptibly()
        Thread.currentThread().interrupt()
        throw e
      }
    }
    finally {
      closed = true
    }

    val first = failures.firstOrNull() ?: return
    for (e in failures) {
      if (e !== first && e !is CancellationException) {
        first.addSuppressed(e)
      }
    }
    throw first
  }

  private fun joinPending() {
    while (true) {
      val pending = forks.filter { !it.future.isDone }
      if (pending.isEmpty()) {
        return
      }
      for (fork in pending) {
        fork.thread.join()
      }
    }
  }

  private fun joinPendingUninterruptibly() {
    while (true) {
      try {
        joinPending()
        return
      }
      catch (_: InterruptedException) {
        // the flag is re-asserted by the caller when every fork has ended
      }
    }
  }

  private class Fork<T> {
    @JvmField val future = CompletableFuture<T>()
    lateinit var thread: Thread

    fun end(scope: TaskScope, result: T?, e: Throwable?) {
      scope.record(this, result, e)
    }
  }
}

private class TaskScopeCancellationException : CancellationException("The group of forks cancelled the fork")

/**
 * Runs [block] with a group of forks, and blocks until every fork has ended.
 *
 * A failure of [block] cancels the forks and is rethrown with the failures of the forks suppressed. A failure of
 * a fork follows [policy]. A fork failure is never hidden behind a cancellation: when a failed fork makes the group
 * cancel a fork that [block] joins, the failure is thrown and not the [CancellationException] that the join saw.
 * This is the replacement for `coroutineScope` and `supervisorScope` in the packaging pipeline.
 */
@ApiStatus.Internal
fun <T> taskScope(
  policy: TaskScopePolicy = TaskScopePolicy.FAIL_FAST,
  block: TaskScope.() -> T,
): T {
  val tasks = TaskScope(policy = policy)
  val result = try {
    tasks.block()
  }
  catch (e: Throwable) {
    tasks.cancelAll()
    val forkFailure = runCatching { tasks.joinAll() }.exceptionOrNull()
    when {
      // a cancellation is never suppressed: the caller's own cancellation must stay a plain one
      forkFailure == null || forkFailure is CancellationException || forkFailure is InterruptedException -> throw e
      // the block saw only the cancellation that the failed fork caused
      e is CancellationException || e is InterruptedException -> throw forkFailure
      else -> {
        e.addSuppressed(forkFailure)
        throw e
      }
    }
  }
  tasks.joinAll()
  return result
}
