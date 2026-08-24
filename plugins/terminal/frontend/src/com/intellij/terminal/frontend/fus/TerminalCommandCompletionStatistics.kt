package com.intellij.terminal.frontend.fus

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import org.jetbrains.plugins.terminal.fus.ReworkedTerminalUsageCollector
import org.jetbrains.plugins.terminal.view.TerminalContentChangeEvent
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
import kotlin.time.Duration.Companion.milliseconds

internal class TerminalCommandCompletionStatistics(private val project: Project) {
  private val metrics = TerminalCommandCompletionMetrics()
  private var commandStartTime: Long? = null
  private var trackedCommandBlockId: TerminalBlockId? = null
  private var previousCommandLength: Int = 0

  fun install(shellIntegration: TerminalShellIntegration, outputModel: TerminalOutputModel, parentDisposable: Disposable) {
    outputModel.addListener(parentDisposable, object : TerminalOutputModelListener {
      override fun afterContentChanged(event: TerminalContentChangeEvent) {
        if (event.isTypeAhead || shellIntegration.outputStatus.value != TerminalOutputStatus.TypingCommand) return

        val commandBlock = shellIntegration.blocksModel.activeBlock as? TerminalCommandBlock ?: return
        if (trackedCommandBlockId != commandBlock.id) {
          metrics.reset()
          trackedCommandBlockId = commandBlock.id
          previousCommandLength = 0
        }
        val currentCommandLength = commandBlock.getTypedCommandText(outputModel)?.length ?: return
        if (currentCommandLength == 0) {
          metrics.reset()
          previousCommandLength = 0
          return
        }
        metrics.recordCommandLengthChanged(previousCommandLength, currentCommandLength)
        previousCommandLength = currentCommandLength
      }
    })

    shellIntegration.addCommandExecutionListener(parentDisposable, object : TerminalCommandExecutionListener {
      override fun commandStarted(event: TerminalCommandStartedEvent) {
        val completionMetrics = metrics.takeAndReset()
        val command = event.commandBlock.executedCommand ?: return
        trackedCommandBlockId = null
        previousCommandLength = 0
        commandStartTime = System.currentTimeMillis()
        ReworkedTerminalUsageCollector.logCommandStarted(
          project,
          command,
          totalCommandInsertedLength = completionMetrics.totalCommandInsertedLength,
          popupCompletionLength = completionMetrics.popupCompletionLength,
          inlineCompletionLength = completionMetrics.inlineCompletionLength,
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

  companion object {
    val KEY: Key<TerminalCommandCompletionStatistics> = Key.create("terminal.command.completion.statistics")
  }
}
