// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.lsp.core.type

import com.intellij.idea.TestFor
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.python.junit5Tests.framework.pyModuleFixture
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.jetbrains.python.psi.PyReferenceExpression
import com.jetbrains.python.psi.PyTypedElement
import com.jetbrains.python.psi.types.PyType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * An LSP server answers for the interpreter and the content roots of one module, so an engine must
 * refuse an element of another module. A file of no module is a library file, which any server can
 * answer for.
 */
@TestApplication
@TestFor(issues = ["PY-91402"])
internal class PyLspTypeEngineTest {
  private val projectFixture = projectFixture(openAfterCreation = true)
  private val mainPath = tempPathFixture()
  private val secondPath = tempPathFixture()
  private val outsidePath = tempPathFixture()
  private val mainModule = projectFixture.pyModuleFixture(mainPath, addPathToSourceRoot = true)
  private val secondModule = projectFixture.pyModuleFixture(secondPath, addPathToSourceRoot = true)

  @Test
  fun `an element of the engine's own module is supported`() = runBlocking {
    val engine = FakeLspTypeEngine(mainModule.get())
    secondModule.get()

    assertTrue(engine.isSupportedForResolve(referenceIn(mainPath.get())))
  }

  @Test
  fun `an element of another module is not supported`() = runBlocking {
    val engine = FakeLspTypeEngine(mainModule.get())
    secondModule.get()

    assertFalse(engine.isSupportedForResolve(referenceIn(secondPath.get())))
  }

  @Test
  fun `the answer does not change when it is asked twice`() = runBlocking {
    val engine = FakeLspTypeEngine(mainModule.get())
    secondModule.get()
    val ownReference = referenceIn(mainPath.get())
    val otherReference = referenceIn(secondPath.get())

    repeat(2) {
      assertTrue(engine.isSupportedForResolve(ownReference))
      assertFalse(engine.isSupportedForResolve(otherReference))
    }
  }

  @Test
  fun `an element of no module is supported, because it is a library file`() = runBlocking {
    val engine = FakeLspTypeEngine(mainModule.get())
    secondModule.get()

    assertTrue(engine.isSupportedForResolve(referenceIn(outsidePath.get())))
  }

  /** A `PyReferenceExpression` in a new `main.py` under [directory]. [LspIsSupportedTypesVisitor] accepts it. */
  private suspend fun referenceIn(directory: Path): PyReferenceExpression {
    val file = directory.resolve("main.py")
    withContext(Dispatchers.IO) {
      file.writeText("value = len(\"a\")\n")
    }
    val virtualFile = withContext(Dispatchers.IO) {
      requireNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(file)) { "No virtual file for $file" }
    }
    return readAction {
      val psiFile = requireNotNull(PsiManager.getInstance(projectFixture.get()).findFile(virtualFile)) { "No PSI for $file" }
      requireNotNull(PsiTreeUtil.findChildOfType(psiFile, PyReferenceExpression::class.java)) {
        "No reference expression in ${psiFile.name}, which parsed as ${psiFile.language}"
      }
    }
  }

  private class FakeLspTypeEngine(override val module: Module) : PyLspTypeEngine {
    override val name: String = "fake"

    override fun resolveType(pyTypedElement: PyTypedElement, isLibrary: Boolean, isUserInitiated: Boolean): Ref<PyType?>? = null
  }
}
