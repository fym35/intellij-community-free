// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import com.dynatrace.hash4j.hashing.Hashing
import kotlinx.coroutines.sync.Mutex
import org.jetbrains.annotations.ApiStatus.Internal
import java.util.concurrent.locks.ReentrantLock

/**
 * A fixed set of locks, one per hash stripe, for a caller that blocks on a virtual thread.
 *
 * The lock of a key is a [ReentrantLock], so the body that holds it must end on the thread that took it.
 * [StripedMutex] is the twin for a body that still suspends.
 */
@Internal
class StripedLock(stripeCount: Int = 64) {
  private val locks = Array(stripeCount) { ReentrantLock() }
  private val mask = (stripeCount - 1).toLong()

  init {
    require(stripeCount > 0) { "Stripe count must be positive" }
    require(stripeCount and (stripeCount - 1) == 0) { "Stripe count must be a power of 2" }
  }

  fun getLock(string: String): ReentrantLock {
    return locks[(Hashing.xxh3_64().hashBytesToLong(string.toByteArray()) and mask).toInt()]
  }

  fun getLockByHash(hash: Long): ReentrantLock {
    return locks[(hash and mask).toInt()]
  }
}

@Internal
class StripedMutex(stripeCount: Int = 64) {
  private val locks = Array(stripeCount) { Mutex() }
  private val mask = (stripeCount - 1).toLong()

  init {
    require(stripeCount > 0) { "Stripe count must be positive" }
    require(stripeCount and (stripeCount - 1) == 0) { "Stripe count must be a power of 2" }
  }

  fun getLock(string: String): Mutex {
    return locks[(Hashing.xxh3_64().hashBytesToLong(string.toByteArray()) and mask).toInt()]
  }

  fun getLockByHash(hash: Long): Mutex {
    return locks[(hash and mask).toInt()]
  }
}
