package com.intellij.platform.buildScripts.testFramework.distributionContent

import com.intellij.testFramework.common.timeoutRunBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.future.asCompletableFuture
import kotlinx.coroutines.launch
import org.assertj.core.api.Assertions.assertThat
import org.jetbrains.intellij.build.joinShared
import org.jetbrains.intellij.build.taskScope
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

@Timeout(30)
class PackagingSuiteCancellationTest {
  @Test
  fun `fixture cancellation interrupts a blocking build and waits for cleanup`(): Unit = timeoutRunBlocking {
    createPackagingSuiteDispatcher().use { dispatcher ->
      val started = CompletableDeferred<Unit>()
      val release = CountDownLatch(1)
      val cleaned = AtomicBoolean()
      val build = launch(dispatcher) {
        runPackagingBuildTask {
          taskScope {
            fork("blocking build") {
              started.complete(Unit)
              try {
                release.await()
              }
              finally {
                cleaned.set(true)
              }
            }
          }
        }
      }
      try {
        started.await()
        build.cancelAndJoin()
        assertThat(build.isCancelled).isTrue()
        assertThat(cleaned.get()).isTrue()
      }
      finally {
        release.countDown()
        build.cancelAndJoin()
      }
    }
  }

  @Test
  fun `a cancelled validator stops waiting without cancelling the shared result`(): Unit = timeoutRunBlocking {
    createPackagingSuiteDispatcher().use { dispatcher ->
      val result = CompletableDeferred<Int>()
      val future = result.asCompletableFuture()
      val started = CompletableDeferred<Unit>()
      val validation = launch(dispatcher) {
        runPackagingBuildTask {
          started.complete(Unit)
          future.joinShared()
        }
      }
      try {
        started.await()
        validation.cancelAndJoin()
        assertThat(validation.isCancelled).isTrue()
        assertThat(result.isActive).isTrue()
        assertThat(future.isDone).isFalse()
        result.complete(42)
        assertThat(future.joinShared()).isEqualTo(42)
      }
      finally {
        result.cancel()
        validation.cancelAndJoin()
      }
    }
  }

  @Test
  fun `blocking work stays on the fixture virtual thread`(): Unit = timeoutRunBlocking {
    createPackagingSuiteDispatcher().use { dispatcher ->
      val result = async(dispatcher) {
        val caller = Thread.currentThread()
        runPackagingBuildTask {
          assertThat(Thread.currentThread()).isSameAs(caller)
          assertThat(caller.isVirtual).isTrue()
          42
        }
      }
      assertThat(result.await()).isEqualTo(42)
    }
  }
}
