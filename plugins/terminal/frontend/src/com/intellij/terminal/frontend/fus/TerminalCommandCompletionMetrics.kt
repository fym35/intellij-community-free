package com.intellij.terminal.frontend.fus

import org.jetbrains.annotations.ApiStatus

/** Collects completion and command-input lengths for one command. */
@ApiStatus.Internal
class TerminalCommandCompletionMetrics {
  private var totalCommandInsertedLength: Int = 0
  private var popupCompletionLength: Int = 0
  private var inlineCompletionLength: Int = 0

  /** Counts only command length growth; removals do not affect the metric. */
  fun recordCommandLengthChanged(previousLength: Int, currentLength: Int) {
    totalCommandInsertedLength += (currentLength - previousLength).coerceAtLeast(0)
  }

  fun recordPopupInserted(length: Int) {
    if (length > 0) popupCompletionLength += length
  }

  fun recordInlineInserted(length: Int) {
    if (length > 0) inlineCompletionLength += length
  }

  fun takeAndReset(): CompletionLengthSnapshot {
    return CompletionLengthSnapshot(totalCommandInsertedLength, popupCompletionLength, inlineCompletionLength).also {
      reset()
    }
  }

  fun reset() {
    totalCommandInsertedLength = 0
    popupCompletionLength = 0
    inlineCompletionLength = 0
  }

  data class CompletionLengthSnapshot(
    val totalCommandInsertedLength: Int,
    val popupCompletionLength: Int,
    val inlineCompletionLength: Int,
  )
}
