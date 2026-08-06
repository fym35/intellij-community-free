// WITH_COROUTINES
// PROBLEM: none
package test

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

// This is currently not reported because we disable the inspection anywhere inside a sequence,
// even though in `runBlocking` a CE can be suppressed.
fun numbers(): Sequence<Int> = sequence {
    val value = runBlocking {
        try {
            delay(100)
            42
        } catch (<caret>e: Exception) {
            0
        }
    }
    yield(value)
}
