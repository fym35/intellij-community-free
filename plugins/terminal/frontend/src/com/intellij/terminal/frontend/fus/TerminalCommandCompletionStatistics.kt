package com.intellij.terminal.frontend.fus

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.terminal.frontend.view.TerminalKeyEvent
import com.intellij.terminal.frontend.view.TerminalKeyEventsListener
import org.jetbrains.plugins.terminal.fus.ReworkedTerminalUsageCollector
import org.jetbrains.plugins.terminal.view.TerminalCursorOffsetChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
import org.jetbrains.plugins.terminal.view.TerminalOutputModelListener
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalBlockId
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandBlock
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandExecutionListener
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandFinishedEvent
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalCommandStartedEvent
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalOutputStatus
import org.jetbrains.plugins.terminal.view.shellIntegration.TerminalShellIntegration
import org.jetbrains.plugins.terminal.view.shellIntegration.getTypedCommandText
import java.awt.event.KeyEvent
import kotlin.time.Duration.Companion.milliseconds

internal class TerminalCommandCompletionStatistics(private val project: Project) {
  private val metrics = TerminalCommandCompletionMetrics()
  private var commandStartTime: Long? = null
  private var trackedCommandBlockId: TerminalBlockId? = null
  private var previousTypedCommandLength: Int? = null
  private var previousCursorOffset: Long? = null
  private var cursorPositionChanged: Boolean = false
  private var cursorProcessingScheduled: Boolean = false
  private var shellIntegration: TerminalShellIntegration? = null

  fun install(
    shellIntegration: TerminalShellIntegration,
    outputModel: TerminalOutputModel,
    isCursorVisible: () -> Boolean,
    parentDisposable: Disposable,
  ) {
    this.shellIntegration = shellIntegration
    outputModel.addListener(parentDisposable, object : TerminalOutputModelListener {
      override fun cursorOffsetChanged(event: TerminalCursorOffsetChangeEvent) {
        cursorPositionChanged = true
        if (cursorProcessingScheduled) return

        cursorProcessingScheduled = true
        invokeLater {
          cursorProcessingScheduled = false
          processCursorPositionChanged(outputModel, isCursorVisible())
        }
      }
    })
    shellIntegration.addCommandExecutionListener(parentDisposable, object : TerminalCommandExecutionListener {
      override fun commandStarted(event: TerminalCommandStartedEvent) {
        val timeMillis = System.currentTimeMillis()
        val completionMetrics = metrics.takeAndReset(timeMillis)
        val command = event.commandBlock.executedCommand ?: return
        trackedCommandBlockId = null
        previousTypedCommandLength = null
        previousCursorOffset = null
        commandStartTime = timeMillis
        val commandTypingTimeMillis = completionMetrics.commandTypingTimeMillis ?: run {
          LOG.warn("Skipping command metrics because typing start time was not recorded")
          return
        }
        ReworkedTerminalUsageCollector.logCommandStarted(
          project,
          command,
          totalCommandInsertedLength = completionMetrics.totalCommandInsertedLength,
          popupCompletionLength = completionMetrics.popupCompletionLength,
          inlineCompletionLength = completionMetrics.inlineCompletionLength,
          typingsCount = completionMetrics.typingsCount,
          backspacesCount = completionMetrics.backspacesCount,
          commandTypingTimeMillis = commandTypingTimeMillis,
        )
      }

      override fun commandFinished(event: TerminalCommandFinishedEvent) {
        val command = event.commandBlock.executedCommand ?: return
        val exitCode = event.commandBlock.exitCode ?: return
        val startTime = commandStartTime ?: return
        commandStartTime = null
        ReworkedTerminalUsageCollector.logCommandFinished(project, command, exitCode, (System.currentTimeMillis() - startTime).milliseconds)
      }
    })
  }

  fun recordPopupInserted(length: Int) {
    metrics.recordPopupInserted(length)
  }

  fun recordInlineInserted(length: Int) {
    metrics.recordInlineInserted(length)
  }

  /** Called after all events in a terminal output batch have been applied. */
  fun processCursorPositionChanged(outputModel: TerminalOutputModel, isCursorVisible: Boolean) {
    if (!cursorPositionChanged) return
    cursorPositionChanged = false
    if (!isCursorVisible) return

    recordCursorPositionChanged(outputModel)
  }

  private fun recordCursorPositionChanged(outputModel: TerminalOutputModel) {
    val commandBlock = getActiveCommandBlock() ?: return
    val commandStartOffset = commandBlock.commandStartOffset ?: return
    if (trackedCommandBlockId != commandBlock.id) {
      metrics.reset()
      trackedCommandBlockId = commandBlock.id
      previousTypedCommandLength = 0
      previousCursorOffset = commandStartOffset.toAbsolute()
    }

    val typedCommandLength = commandBlock.getTypedCommandText(outputModel)?.length ?: return
    val cursorOffset = outputModel.cursorOffset.toAbsolute()
    if (typedCommandLength == 0) {
      metrics.reset()
      previousTypedCommandLength = 0
      previousCursorOffset = cursorOffset
      return
    }

    val modelDelta = (typedCommandLength - checkNotNull(previousTypedCommandLength)).coerceAtLeast(0)
    val cursorDelta = (cursorOffset - checkNotNull(previousCursorOffset)).coerceAtLeast(0)
    val delta = minOf(modelDelta.toLong(), cursorDelta).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    metrics.recordCommandTextInserted(delta)
    previousTypedCommandLength = typedCommandLength
    previousCursorOffset = cursorOffset
  }

  override fun afterKeyEvent(event: TerminalKeyEvent) {
    if (shellIntegration?.outputStatus?.value != TerminalOutputStatus.TypingCommand) return

    val awtEvent = event.awtEvent
    when {
      awtEvent.id == KeyEvent.KEY_TYPED -> {
        metrics.recordTyping(awtEvent.`when`)
      }
      awtEvent.id == KeyEvent.KEY_PRESSED && awtEvent.keyCode == KeyEvent.VK_BACK_SPACE -> {
        metrics.recordBackspace(awtEvent.`when`)
      }
    }
  }

  private fun getActiveCommandBlock(): TerminalCommandBlock? {
    val shellIntegration = shellIntegration ?: return null
    if (shellIntegration.outputStatus.value != TerminalOutputStatus.TypingCommand) return null
    return shellIntegration.blocksModel.activeBlock as? TerminalCommandBlock
  }

  companion object {
    val KEY: Key<TerminalCommandCompletionStatistics> = Key.create("terminal.command.completion.statistics")
    private val LOG = logger<TerminalCommandCompletionStatistics>()
  }
}
