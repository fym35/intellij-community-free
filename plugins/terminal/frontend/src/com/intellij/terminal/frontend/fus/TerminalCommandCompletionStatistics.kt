package com.intellij.terminal.frontend.fus

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.terminal.frontend.view.TerminalKeyEvent
import com.intellij.terminal.frontend.view.TerminalKeyEventsListener
import com.intellij.util.asDisposable
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.plugins.terminal.fus.ReworkedTerminalUsageCollector
import org.jetbrains.plugins.terminal.block.ui.TerminalUiUtils
import org.jetbrains.plugins.terminal.view.TerminalOutputModel
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

internal class TerminalCommandCompletionStatistics private constructor(
  private val project: Project,
  private val shellIntegration: TerminalShellIntegration,
) {
  private val metrics = TerminalCommandCompletionMetrics()
  private var commandStartTime: Long? = null
  private var trackedCommandBlockId: TerminalBlockId? = null
  private var previousTypedCommandLength: Int = 0
  private var previousCursorOffset: Long = 0

  companion object {
    val KEY: Key<TerminalCommandCompletionStatistics> = Key.create("terminal.command.completion.statistics")
    private val LOG = logger<TerminalCommandCompletionStatistics>()

    fun install(
      project: Project,
      shellIntegration: TerminalShellIntegration,
      outputModel: TerminalOutputModel,
      isCursorVisible: () -> Boolean,
      registerKeyEventsListener: (Disposable, TerminalKeyEventsListener) -> Unit,
      coroutineScope: CoroutineScope,
    ): TerminalCommandCompletionStatistics {
      val statistics = TerminalCommandCompletionStatistics(project, shellIntegration)
      statistics.installListeners(outputModel, isCursorVisible, registerKeyEventsListener, coroutineScope)
      return statistics
    }
  }

  private fun installListeners(
    outputModel: TerminalOutputModel,
    isCursorVisible: () -> Boolean,
    registerKeyEventsListener: (Disposable, TerminalKeyEventsListener) -> Unit,
    coroutineScope: CoroutineScope,
  ) {
    TerminalUiUtils.listenOutputModelChanges(outputModel, coroutineScope) {
      if (isCursorVisible()) {
        recordCommandTextChanged(outputModel)
      }
    }
    val parentDisposable = coroutineScope.asDisposable()
    shellIntegration.addCommandExecutionListener(parentDisposable, object : TerminalCommandExecutionListener {
      override fun commandStarted(event: TerminalCommandStartedEvent) {
        val timeMillis = System.currentTimeMillis()
        val completionMetrics = metrics.takeAndReset(timeMillis)
        val command = event.commandBlock.executedCommand ?: return
        trackedCommandBlockId = null
        previousTypedCommandLength = 0
        previousCursorOffset = 0
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
    registerKeyEventsListener(parentDisposable, object : TerminalKeyEventsListener {
      override fun afterKeyEvent(event: TerminalKeyEvent) {
        recordKeyEvent(event)
      }
    })
  }

  fun recordPopupInserted(length: Int) {
    metrics.recordPopupInserted(length)
  }

  fun recordInlineInserted(length: Int) {
    metrics.recordInlineInserted(length)
  }

  /** Records command text changes after current terminal output updates. */
  private fun recordCommandTextChanged(outputModel: TerminalOutputModel) {
    val commandBlock = getActiveCommandBlock() ?: return
    val commandStartOffset = commandBlock.commandStartOffset ?: return
    if (trackedCommandBlockId != commandBlock.id) {
      metrics.reset()
      trackedCommandBlockId = commandBlock.id
      previousTypedCommandLength = 0
      previousCursorOffset = commandStartOffset.toAbsolute()
    }

    val cursorOffset = outputModel.cursorOffset.toAbsolute()
    if (cursorOffset == previousCursorOffset) return

    val typedCommandLength = commandBlock.getTypedCommandText(outputModel)?.length ?: return
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

  private fun recordKeyEvent(event: TerminalKeyEvent) {
    if (shellIntegration.outputStatus.value != TerminalOutputStatus.TypingCommand) return

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
    if (shellIntegration.outputStatus.value != TerminalOutputStatus.TypingCommand) return null
    return shellIntegration.blocksModel.activeBlock as? TerminalCommandBlock
  }

}
