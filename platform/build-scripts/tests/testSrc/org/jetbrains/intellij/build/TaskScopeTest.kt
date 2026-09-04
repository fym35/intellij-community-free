// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import com.intellij.util.ref.GCUtil
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TaskScopeTest {
  @Test
  fun `a fork runs on a virtual thread named after the fork and returns its value`() {
    val (isVirtual, threadName) = taskScope {
      fork("worker") { Thread.currentThread().isVirtual to Thread.currentThread().name }.join()
    }

    assertThat(isVirtual).isTrue()
    assertThat(threadName).isEqualTo("worker")
  }

  @Test
  fun `the group waits for a fork that nobody joins`() {
    val done = CompletableFuture<Unit>()
    taskScope {
      fork("late") {
        Thread.sleep(100)
        done.complete(Unit)
      }
    }

    assertThat(done).isCompleted
  }

  @Test
  fun `fail fast interrupts the other forks and rethrows the first failure`() {
    val siblingInterrupted = CompletableFuture<Unit>()
    assertThatThrownBy {
      taskScope {
        fork("sibling") {
          try {
            Thread.sleep(10_000)
          }
          catch (e: InterruptedException) {
            siblingInterrupted.complete(Unit)
            throw e
          }
        }
        fork("failing") {
          Thread.sleep(50)
          throw IllegalStateException("the fork failed")
        }
      }
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessage("the fork failed")
      // the interrupt of the sibling is the cancellation, not a second failure
      .satisfies({ e -> assertThat(e.suppressed).isEmpty() })

    assertThat(siblingInterrupted.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(Unit)
  }

  @Test
  fun `a cancellation thrown by a fork is a failure`() {
    val failure = CancellationException("the fork cancelled itself")

    assertThatThrownBy {
      taskScope {
        fork("failing") { throw failure }
      }
    }.isSameAs(failure)
  }

  /** The block joins a fork that the group cancels because a sibling failed. The failure is thrown, not the cancellation. */
  @Test
  fun `a failure of a fork that the block does not join wins over the cancellation the block sees`() {
    assertThatThrownBy {
      taskScope {
        val slow = fork("slow") { Thread.sleep(10_000) }
        fork("failing") {
          Thread.sleep(50)
          throw IllegalStateException("the fork failed")
        }
        slow.join()
      }
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessage("the fork failed")
  }

  /** As in `StructuredTaskScope`: what a fork reports after the cancel is ignored, an interrupt or any other exception. */
  @Test
  fun `a failure reported after the cancel is ignored`() {
    val secondStarted = CountDownLatch(1)
    assertThatThrownBy {
      taskScope {
        fork("first") {
          secondStarted.await()
          throw IllegalStateException("first")
        }
        fork("second") {
          secondStarted.countDown()
          try {
            Thread.sleep(10_000)
          }
          catch (_: InterruptedException) {
            throw IllegalArgumentException("second")
          }
        }
      }
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessage("first")
      .satisfies({ e -> assertThat(e.suppressed).isEmpty() })
  }

  @Test
  fun `a failure of the block carries the failure of a fork as suppressed`() {
    val forkFailed = CompletableFuture<Unit>()
    assertThatThrownBy {
      taskScope(TaskScopePolicy.RUN_ALL) {
        fork("failing") {
          try {
            throw IllegalStateException("the fork failed")
          }
          finally {
            forkFailed.complete(Unit)
          }
        }
        forkFailed.join()
        throw IllegalArgumentException("the block failed")
      }
    }
      .isInstanceOf(IllegalArgumentException::class.java)
      .hasMessage("the block failed")
      .satisfies({ e ->
        assertThat(e.suppressed).hasSize(1)
        assertThat(e.suppressed[0]).hasMessage("the fork failed")
      })
  }

  /** A fork that a cancelled fork starts must not outlive the cancel. A fork started after the cancel never runs. */
  @Test
  fun `a fork started after the group was cancelled is not started`() {
    val lateForkRan = AtomicBoolean()
    assertThatThrownBy {
      taskScope {
        val group = this
        fork("starter") {
          try {
            Thread.sleep(10_000)
          }
          catch (e: InterruptedException) {
            group.fork("late") {
              lateForkRan.set(true)
            }
            throw e
          }
        }
        fork("failing") {
          Thread.sleep(50)
          throw IllegalStateException("the fork failed")
        }
      }
    }.isInstanceOf(IllegalStateException::class.java)

    assertThat(lateForkRan.get()).isFalse()
  }

  /** A body that swallows the interrupt runs to its end, and the group waits for it. */
  @Test
  fun `the group waits for the body of a cancelled fork to end`() {
    val slowEnded = AtomicBoolean()
    assertThatThrownBy {
      taskScope {
        fork("slow") {
          try {
            Thread.sleep(300)
          }
          catch (_: InterruptedException) {
            Thread.sleep(300)
          }
          slowEnded.set(true)
        }
        fork("failing") { throw IllegalStateException("the fork failed") }
      }
    }.isInstanceOf(IllegalStateException::class.java)

    assertThat(slowEnded.get()).isTrue()
  }

  @Test
  fun `run all lets every fork finish and reports every failure`() {
    val slowFinished = CompletableFuture<Unit>()
    assertThatThrownBy {
      taskScope(TaskScopePolicy.RUN_ALL) {
        fork("failing") { throw IllegalStateException("first") }
        fork("slow") {
          Thread.sleep(200)
          slowFinished.complete(Unit)
        }
        fork("failing too") {
          Thread.sleep(50)
          throw IllegalArgumentException("second")
        }
      }
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessage("first")
      .satisfies({ e ->
        assertThat(e.suppressed).hasSize(1)
        assertThat(e.suppressed[0]).hasMessage("second")
      })

    assertThat(slowFinished).isCompleted
  }

  /** As `close` of `StructuredTaskScope`: an interrupted owner cancels the forks, waits for them, and keeps its interrupt. */
  @Test
  fun `an interrupted caller cancels the forks and keeps the interrupt flag`() {
    val forkInterrupted = CompletableFuture<Unit>()
    val forkEnded = AtomicBoolean()
    val callerFlag = CompletableFuture<Boolean>()
    val caller = Thread.ofVirtual().start {
      try {
        taskScope {
          fork("endless") {
            try {
              Thread.sleep(10_000)
            }
            catch (e: InterruptedException) {
              forkInterrupted.complete(Unit)
              Thread.sleep(100)
              forkEnded.set(true)
              throw e
            }
          }
        }
        callerFlag.complete(false)
      }
      catch (_: InterruptedException) {
        callerFlag.complete(Thread.currentThread().isInterrupted)
      }
    }
    Thread.sleep(100)
    caller.interrupt()

    assertThat(forkInterrupted.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(Unit)
    assertThat(callerFlag.orTimeout(5, TimeUnit.SECONDS).join()).isTrue()
    // the caller left the group only when the fork had ended
    assertThat(forkEnded.get()).isTrue()
  }

  /** The outer cancel interrupts the fork thread, and the inner group it runs cancels its own forks. */
  @Test
  fun `a nested group is cancelled with its fork`() {
    val innerInterrupted = CompletableFuture<Unit>()
    assertThatThrownBy {
      taskScope {
        fork("outer") {
          taskScope {
            fork("inner") {
              try {
                Thread.sleep(10_000)
              }
              catch (e: InterruptedException) {
                innerInterrupted.complete(Unit)
                throw e
              }
            }
          }
        }
        fork("failing") {
          Thread.sleep(50)
          throw IllegalStateException("the fork failed")
        }
      }
    }
      .isInstanceOf(IllegalStateException::class.java)
      .hasMessage("the fork failed")

    assertThat(innerInterrupted.orTimeout(5, TimeUnit.SECONDS).join()).isEqualTo(Unit)
  }

  /** The span helpers make the telemetry context current on the thread, and a fork copies it. */
  @Test
  fun `a fork sees the telemetry context of the caller`() {
    val key = ContextKey.named<String>("TaskScopeTest")
    val seen = Context.current().with(key, "from the caller").makeCurrent().use {
      taskScope {
        fork("reader") { Context.current().get(key) }.join()
      }
    }

    assertThat(seen).isEqualTo("from the caller")
  }

  /** A fork inside a single-flight computation stays inside it, so a recursive wait still fails fast. */
  @Test
  fun `a fork sees the single-flight owners of the caller`() {
    val owner = Any()
    val seen = withSingleFlightOwners(inherited = emptySet(), owner = owner) {
      taskScope {
        fork("reader") { currentSingleFlightOwners() }.join()
      }
    }

    assertThat(seen).containsExactly(owner)
  }

  @Test
  fun `a completed subtask hands out its value`() {
    assertThat(Subtask.completed(42).join()).isEqualTo(42)
  }

  /** A dead virtual thread keeps its task, so the fork must drop the task before it runs it. */
  @Test
  fun `a finished fork keeps nothing alive through its thread`() {
    val forkThread = AtomicReference<Thread>()
    val payload = runForkThatCapturesAPayload(forkThread)
    val thread = forkThread.get()

    GCUtil.tryGcSoftlyReachableObjects()

    // the thread stays reachable through this local until here
    assertThat(thread.name).isEqualTo("holder")
    assertThat(payload.get()).isNull()
  }

  /** The payload is reachable only from the fork body, so only the dead thread can keep it. */
  private fun runForkThatCapturesAPayload(forkThread: AtomicReference<Thread>): WeakReference<Any> {
    val payload = Any()
    taskScope {
      fork("holder") {
        forkThread.set(Thread.currentThread())
        payload.hashCode()
      }
    }
    return WeakReference(payload)
  }
}
