package com.intellij.terminal.tests.reworked.frontend.fus

import com.intellij.terminal.frontend.fus.TerminalCommandCompletionMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

internal class TerminalCommandCompletionMetricsTest {
  @Test
  fun `counts initial typed command`() {
    val metrics = TerminalCommandCompletionMetrics()

    metrics.recordCommandLengthChanged(0, 4)

    assertThat(metrics.takeAndReset()).isEqualTo(snapshot(totalCommandInsertedLength = 4))
  }

  @Test
  fun `counts typed characters appended to command`() {
    val metrics = TerminalCommandCompletionMetrics()

    metrics.recordCommandLengthChanged(4, 12)

    assertThat(metrics.takeAndReset()).isEqualTo(snapshot(totalCommandInsertedLength = 8))
  }

  @Test
  fun `does not subtract deleted text`() {
    val metrics = TerminalCommandCompletionMetrics()

    metrics.recordCommandLengthChanged(0, 12)
    metrics.recordCommandLengthChanged(12, 6)

    assertThat(metrics.takeAndReset()).isEqualTo(snapshot(totalCommandInsertedLength = 12))
  }

  @Test
  fun `counts popup completion separately`() {
    val metrics = TerminalCommandCompletionMetrics()

    metrics.recordPopupInserted(10)

    assertThat(metrics.takeAndReset()).isEqualTo(snapshot(popupCompletionLength = 10))
  }

  @Test
  fun `counts inline completion separately`() {
    val metrics = TerminalCommandCompletionMetrics()

    metrics.recordInlineInserted(3)

    assertThat(metrics.takeAndReset()).isEqualTo(snapshot(inlineCompletionLength = 3))
  }

  @Test
  fun `resets metrics for next command`() {
    val metrics = TerminalCommandCompletionMetrics()

    metrics.recordCommandLengthChanged(0, 4)
    metrics.recordPopupInserted(10)
    metrics.recordInlineInserted(3)
    metrics.takeAndReset()

    assertThat(metrics.takeAndReset()).isEqualTo(snapshot())
  }

  private fun snapshot(
    totalCommandInsertedLength: Int = 0,
    popupCompletionLength: Int = 0,
    inlineCompletionLength: Int = 0,
  ): TerminalCommandCompletionMetrics.CompletionLengthSnapshot {
    return TerminalCommandCompletionMetrics.CompletionLengthSnapshot(
      totalCommandInsertedLength = totalCommandInsertedLength,
      popupCompletionLength = popupCompletionLength,
      inlineCompletionLength = inlineCompletionLength,
    )
  }
}
