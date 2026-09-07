@file:OptIn(ExperimentalCoroutinesApi::class)

package com.intellij.python.processOutput

import com.intellij.platform.util.coroutines.childScope
import com.intellij.python.processOutput.common.ExecErrorDto
import com.intellij.python.processOutput.common.ExecErrorReasonDto
import com.intellij.python.processOutput.common.ExecutableDto
import com.intellij.python.processOutput.common.FrontendTopicListener
import com.intellij.python.processOutput.common.LoggedProcessDto
import com.intellij.python.processOutput.common.OutputKindDto
import com.intellij.python.processOutput.common.OutputLineDto
import com.intellij.python.processOutput.common.ProcessBinaryFileName
import com.intellij.python.processOutput.common.ProcessIcon
import com.intellij.python.processOutput.common.ProcessMatcher
import com.intellij.python.processOutput.common.ProcessOutputEventDto
import com.intellij.python.processOutput.common.TraceContextDto
import com.intellij.python.processOutput.common.TraceContextKind
import com.intellij.python.processOutput.common.TraceContextUuid
import com.intellij.python.processOutput.frontend.CoroutineNames
import com.intellij.python.processOutput.frontend.LoggedProcess
import com.intellij.python.processOutput.frontend.OutputFilter
import com.intellij.python.processOutput.frontend.ProcessOutputControllerImpl
import com.intellij.python.processOutput.frontend.ProcessOutputControllerServiceLimits
import com.intellij.python.processOutput.frontend.ProcessOutputIconMappingData
import com.intellij.python.processOutput.frontend.ProcessStatus
import com.intellij.python.processOutput.frontend.ProcessTreeNode
import com.intellij.python.processOutput.frontend.TreeFilter
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.common.waitUntil
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.debug.DebugProbes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private class ProcessOutputControllerImplTest {
  @Test
  fun `process limit is maintained`() = runOutputControllerImplTest(10.minutes) {
    // adding MAX_PROCESSES amount of processes
    repeat(limits.MAX_PROCESSES) {
      addProcess(it)
    }

    // the size of logged processes should equal to MAX_PROCESSES
    assertEquals(limits.MAX_PROCESSES, controller.loggedProcesses.value.size)

    // adding MAX_PROCESSES * 2 amount of processes
    repeat(limits.MAX_PROCESSES * 2) {
      addProcess(limits.MAX_PROCESSES + it)
    }

    // the size of logged processes should STILL equal to MAX_PROCESSES
    assertEquals(limits.MAX_PROCESSES, controller.loggedProcesses.value.size)
  }

  @Test
  fun `line limit is maintained`() = runOutputControllerImplTest(10.minutes) {
    val process = addProcess(0)

    // adding MAX_LINES amount of OUT lines
    repeat(limits.MAX_LINES) {
      process.addOutLine("out$it")
    }

    // should find last added line, and the size of all lines should equal to MAX_LINES
    waitUntil {
      process.lastLine?.let { it.text == "out${limits.MAX_LINES - 1}" && it.kind == OutputKindDto.OUT } == true
    }
    assertEquals(limits.MAX_LINES, process.lines.value.size)

    // adding MAX_LINES amount of ERR lines
    repeat(limits.MAX_LINES) {
      process.addErrLine("err${it + limits.MAX_LINES}")
    }

    // should find last added line, and the size of all lines should STILL equal to MAX_LINES
    waitUntil {
      process.lastLine?.let { it.text == "err${limits.MAX_LINES * 2 - 1}" && it.kind == OutputKindDto.ERR } == true
    }
    assertEquals(limits.MAX_LINES, process.lines.value.size)

    // adding MAX_LINES amount of OUT lines again
    repeat(limits.MAX_LINES) {
      process.addOutLine("out${it + limits.MAX_LINES * 2}")
    }

    // should find last added line, and the size of all lines should STILL equal to MAX_LINES
    waitUntil {
      process.lastLine?.let { it.text == "out${limits.MAX_LINES * 3 - 1}" && it.kind == OutputKindDto.OUT } == true
    }
    assertEquals(limits.MAX_LINES, process.lines.value.size)
  }

  @Test
  fun `exit info collector coroutines get properly cleaned up`() = runOutputControllerImplTest(10.minutes) {
    // no coroutines should be active
    assert(exitInfoCollectorCoroutinesCount() == 0)

    // spawn 1024 processes, instantly terminate them
    repeat(1024) {
      val process = addProcess(it, traceContext = nonInteractiveTraceContextDto)
      process.exit(0)
    }

    // no coroutines should be active
    waitUntil { exitInfoCollectorCoroutinesCount() == 0 }

    // spawn 100 processes
    val processes = mutableListOf<LoggedProcess>()
    repeat(100) {
      processes += addProcess(it + 1024, traceContext = nonInteractiveTraceContextDto)
    }

    // 100 coroutines should be active
    waitUntil(
      { "actual: ${exitInfoCollectorCoroutinesCount()}" }
    ) { exitInfoCollectorCoroutinesCount() == 100 }

    // terminating all processes
    for (process in processes) {
      process.exit(0)
    }

    // updating the flow by adding and terminating one process
    val process = addProcess(9999)
    process.exit(0)

    // no coroutines should be active
    waitUntil { exitInfoCollectorCoroutinesCount() == 0 }
  }

  @Test
  fun `tag section and exit info copy buttons work correctly`() = runOutputControllerImplTest {
    val process = addProcess(0)

    repeat(6) {
      process.addOutLine("out$it")
    }

    repeat(4) {
      process.addErrLine("err${it + 6}")
    }

    process.exit(0)

    // copy stdout section 0..5
    controller.copyOutputTagAtIndexToClipboard(process, 0)

    assertEquals(
      """
        out0
        out1
        out2
        out3
        out4
        out5
        
      """.trimIndent(),
      clipboardStrings[0]
    )

    // copy stderr section 6..9
    controller.copyOutputTagAtIndexToClipboard(process, 6)

    Assertions.assertEquals(
      """
        err6
        err7
        err8
        err9
        
      """.trimIndent(),
      clipboardStrings[1],
    )

    // exit info without additional message
    controller.copyOutputExitInfoToClipboard(process)

    Assertions.assertEquals(
      """
        0
        
      """.trimIndent(),
      clipboardStrings[2],
    )

    // exit info with additional message
    process.setAdditionalInfo("some test message")

    waitUntil {
      when (val status = process.status.value) {
        is ProcessStatus.Done -> status.additionalMessageToUser != null
        ProcessStatus.Running -> false
      }
    }

    controller.copyOutputExitInfoToClipboard(process)

    Assertions.assertEquals(
      """
        0: some test message
        
      """.trimIndent(),
      clipboardStrings[3],
    )
  }

  @Test
  fun `toolbar copy includes tags depending on whether the filter is enabled`() = runOutputControllerImplTest {
    val process = addProcess(0)

    repeat(6) {
      process.addOutLine("out$it")
    }

    repeat(4) {
      process.addErrLine("err${it + 6}")
    }

    process.exit(0)

    // copying output
    controller.copyOutputToClipboard(process)

    // copied output should include tags
    assertEquals(
      """
        [stdout] out0
                 out1
                 out2
                 out3
                 out4
                 out5
        [stderr] err6
                 err7
                 err8
                 err9
          [exit] 0
        
      """.trimIndent(),
      clipboardStrings[0],
    )

    // toggling the show tags filter
    controller.outputSectionState.filters[OutputFilter.Item.SHOW_TAGS] = false

    // copying output
    controller.copyOutputToClipboard(process)

    // copied output should not include tags
    assertEquals(
      """
        out0
        out1
        out2
        out3
        out4
        out5
        err6
        err7
        err8
        err9
        0
        
      """.trimIndent(),
      clipboardStrings[1]
    )
  }

  @Test
  fun `non-ascii output lines are reflected properly`() = runOutputControllerImplTest {
    val nonAsciiText = "Привет, Мир"
    val asciiText = "Hello, world!"

    val process = addProcess(0)
    process.addOutLine(nonAsciiText)
    process.addOutLine(asciiText)

    waitUntil { process.lines.value.size == 2 }

    assertEquals(nonAsciiText, process.lines.value[0].text)
    assertEquals(asciiText, process.lines.value[1].text)
  }

  @Test
  fun `tree is built with nested contexts and root-level processes`() = runOutputControllerImplTest {
    val parentContext = traceContextDto("parent context")
    val childContext = traceContextDto("child context", parentContext.uuid)

    val process1 = addProcess(0)
    val process2 = addProcess(1, traceContext = parentContext, traceHierarchy = listOf(parentContext))
    val process3 = addProcess(2, traceContext = childContext, traceHierarchy = listOf(childContext, parentContext))
    val process4 = addProcess(3, traceContext = parentContext, traceHierarchy = listOf(parentContext))

    // wait until root has two expected entries: Context(parentContext) and Process(process1)
    lateinit var rootLevel: List<ProcessTreeNode>
    waitUntil {
      rootLevel = controller.treeSectionState.treeRoot.value
      rootLevel.size >= 2
      && rootLevel.any { it is ProcessTreeNode.Context && it.uuid == parentContext.uuid }
      && rootLevel.any { it is ProcessTreeNode.Process && it.loggedProcess.data.id == process1.data.id }
    }

    // two root items: parentContext and process1
    Assertions.assertEquals(2, rootLevel.size)
    val parentContextNode = rootLevel[0] as ProcessTreeNode.Context
    val process1Node = rootLevel[1] as ProcessTreeNode.Process
    Assertions.assertEquals(parentContext.uuid, parentContextNode.uuid)
    Assertions.assertEquals(process1.data.id, process1Node.loggedProcess.data.id)

    // parentContext's children: process4, childContext, process2
    val parentContextChildren = parentContextNode.children().toList().filterIsInstance<ProcessTreeNode>()
    Assertions.assertEquals(3, parentContextChildren.size)
    Assertions.assertEquals(process4.data.id, (parentContextChildren[0] as ProcessTreeNode.Process).loggedProcess.data.id)
    val childContextNode = parentContextChildren[1] as ProcessTreeNode.Context
    Assertions.assertEquals(childContext.uuid, childContextNode.uuid)
    Assertions.assertEquals(process2.data.id, (parentContextChildren[2] as ProcessTreeNode.Process).loggedProcess.data.id)

    // childContext's children: process3
    val childContextChildren = childContextNode.children().toList().filterIsInstance<ProcessTreeNode>()
    Assertions.assertEquals(1, childContextChildren.size)
    Assertions.assertEquals(process3.data.id, (childContextChildren[0] as ProcessTreeNode.Process).loggedProcess.data.id)
  }

  @Test
  fun `tree is rebuilt on search query change`() = runOutputControllerImplTest {
    val pythonProcess = addProcess(0, exeParts = listOf("testpython.py"))
    val nodeProcess = addProcess(1, exeParts = listOf("testnode.py"))
    val cargoProcess = addProcess(2, exeParts = listOf("testcargo.py"))

    val processIdsInTree = {
      controller.treeSectionState.treeRoot.value
        .filterIsInstance<ProcessTreeNode.Process>()
        .map { it.loggedProcess.data.id }
        .toSet()
    }

    // default empty search: all three processes are visible
    waitUntil { processIdsInTree() == setOf(pythonProcess.data.id, nodeProcess.data.id, cargoProcess.data.id) }

    // "python" matches only the python exe
    controller.search("testpython")
    waitUntil { processIdsInTree() == setOf(pythonProcess.data.id) }

    // search is case-insensitive: "NODE" still matches the node exe
    controller.search("TESTNODE")
    waitUntil { processIdsInTree() == setOf(nodeProcess.data.id) }

    // substring match: "estcarg" is a substring of "testcargo" only
    controller.search("estcarg")
    waitUntil { processIdsInTree() == setOf(cargoProcess.data.id) }

    // tree is empty when no matches were found
    controller.search("nothingatall")
    waitUntil { processIdsInTree().isEmpty() }

    // clearing the query brings every process back
    controller.search("")
    waitUntil {
      processIdsInTree() == setOf(pythonProcess.data.id, nodeProcess.data.id, cargoProcess.data.id)
    }
  }

  @Test
  fun `tree hides background processes when SHOW_BACKGROUND_PROCESSES filter is toggled`() = runOutputControllerImplTest {
    val backgroundContext = nonInteractiveTraceContextDto
    val interactiveContext = traceContextDto("interactive context")

    val backgroundProcess = addProcess(0, traceContext = backgroundContext)
    addProcess(1, traceContext = interactiveContext)

    val nodeIdsInTree = {
      controller.treeSectionState.treeRoot.value
        .map { it.id }
        .toSet()
    }

    // by default, background processes are hidden
    waitUntil {
      nodeIdsInTree() == setOf(interactiveContext.treeId)
    }

    // enabling the filter makes background processes visible
    controller.treeSectionState.filters[TreeFilter.Item.SHOW_BACKGROUND_PROCESSES] = true
    waitUntil {
      nodeIdsInTree() == setOf(interactiveContext.treeId, backgroundProcess.treeId)
    }

    // disabling it again hides the background process
    controller.treeSectionState.filters[TreeFilter.Item.SHOW_BACKGROUND_PROCESSES] = false
    waitUntil {
      nodeIdsInTree() == setOf(interactiveContext.treeId)
    }
  }

  companion object {
    val limits = ProcessOutputControllerServiceLimits

    @JvmStatic
    @BeforeAll
    fun beforeAll() {
      DebugProbes.install()
    }

    fun createProcessDto(
      id: Int,
      exeParts: List<String> = listOf("bin", "exe"),
      traceContext: TraceContextUuid? = null,
    ) =
      LoggedProcessDto(
        weight = null,
        traceContextUuid = traceContext,
        pid = null,
        startedAt = Instant.fromEpochMilliseconds(0),
        cwd = null,
        exe = ExecutableDto(
          path = exeParts.joinToString("/"),
          parts = exeParts,
        ),
        args = emptyList(),
        env = emptyMap(),
        target = "",
        id = id,
      )

    private class TestContext(
      val controller: ProcessOutputControllerImpl,
      val eventsFlow: MutableSharedFlow<ProcessOutputEventDto>,
      val clipboardStrings: List<String>,
    ) {
      suspend fun addProcess(
        id: Int,
        exeParts: List<String> = listOf("bin", "exe"),
        traceContext: TraceContextDto? = null,
        traceHierarchy: List<TraceContextDto> = traceContext?.let { listOf(it) } ?: emptyList(),
      ): LoggedProcess {
        eventsFlow.emit(
          ProcessOutputEventDto.NewProcess(
            loggedProcess = createProcessDto(id, exeParts = exeParts, traceContext = traceContext?.uuid),
            traceHierarchy = traceHierarchy
          )
        )

        var loggedProcess: LoggedProcess? = null

        waitUntil {
          loggedProcess = controller.loggedProcesses.value.findLast { it.data.id == id }
          loggedProcess != null
        }

        return loggedProcess!!
      }

      suspend fun LoggedProcess.addOutLine(text: String) {
        eventsFlow.emit(
          ProcessOutputEventDto.NewOutputLine(
            processId = data.id,
            outputLine =
              OutputLineDto(
                kind = OutputKindDto.OUT,
                text = text
              )
          )
        )
      }

      suspend fun LoggedProcess.setAdditionalInfo(text: String) {
        eventsFlow.emit(
          ProcessOutputEventDto.ExecError(
            ExecErrorDto(
              message = "",
              command = "",
              reason = ExecErrorReasonDto.Timeout,
              loggedProcessId = data.id,
              additionalMessageToUser = text
            )
          )
        )
      }

      suspend fun LoggedProcess.addErrLine(text: String) {
        eventsFlow.emit(
          ProcessOutputEventDto.NewOutputLine(
            processId = data.id,
            outputLine =
              OutputLineDto(
                kind = OutputKindDto.ERR,
                text = text
              )
          )
        )
      }

      suspend fun LoggedProcess.exit(exitCode: Int, exitedAt: Instant = Instant.fromEpochMilliseconds(0)) {
        eventsFlow.emit(
          ProcessOutputEventDto.ProcessExit(
            processId = data.id,
            exitedAt = exitedAt,
            exitValue = exitCode
          )
        )
      }

      val LoggedProcess.lastLine
        get() =
          lines.value.lastOrNull()
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun runOutputControllerImplTest(
      timeout: Duration = 10.seconds,
      testBody: suspend TestContext.() -> Unit,
    ) =
      timeoutRunBlocking(timeout) {
        val scope = GlobalScope.childScope("Test")
        val eventsFlow = MutableSharedFlow<ProcessOutputEventDto>()
        val copiedStrings = mutableListOf<String>()
        val controller = ProcessOutputControllerImpl(
          coroutineScope = scope,
          frontendTopic =
            object : FrontendTopicListener {
              override val events: Flow<ProcessOutputEventDto> = eventsFlow
            },
          iconMappingData =
            object : ProcessOutputIconMappingData {
              override val mapping: Map<ProcessBinaryFileName, ProcessIcon> = emptyMap()
              override val matchers: List<ProcessMatcher> = emptyList()
            },
          usageCollector = {},
          clipboardCopier = { copiedStrings += it }
        )

        TestContext(controller, eventsFlow, copiedStrings).testBody()
      }

    private fun exitInfoCollectorCoroutinesCount(): Int =
      DebugProbes.dumpCoroutinesInfo()
        .filter { it.context[CoroutineName.Key]?.name == CoroutineNames.EXIT_INFO_COLLECTOR }
        .size

    private fun traceContextDto(title: String, parentUuid: TraceContextUuid? = null) =
      TraceContextDto(
        title = title,
        timestamp = 0,
        uuid = TraceContextUuid(UUID.randomUUID().toString()),
        kind = TraceContextKind.INTERACTIVE,
        parentUuid = parentUuid
      )

    private val nonInteractiveTraceContextDto: TraceContextDto =
      TraceContextDto(
        title = "non interactive",
        timestamp = 0,
        uuid = TraceContextUuid("aaa-bbb-ccc"),
        kind = TraceContextKind.NON_INTERACTIVE,
        parentUuid = null,
      )

    private val TraceContextDto.treeId
      get() =
        ProcessTreeNode.Id.Context(uuid)

    private val LoggedProcess.treeId
      get() =
        ProcessTreeNode.Id.Process(data.id)
  }
}