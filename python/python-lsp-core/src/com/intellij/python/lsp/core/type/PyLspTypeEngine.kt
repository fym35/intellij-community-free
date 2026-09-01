package com.intellij.python.lsp.core.type

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleUtilCore
import com.jetbrains.python.psi.PyExpressionCodeFragment
import com.jetbrains.python.psi.PyTypedElement
import com.jetbrains.python.psi.types.engine.PyTypeEngine
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
interface PyLspTypeEngine : PyTypeEngine {
  /** The module whose LSP server answers for this engine. */
  val module: Module

  override fun isSupportedForResolve(pyTypedElement: PyTypedElement): Boolean {
    val realFile = pyTypedElement.containingFile?.originalFile ?: return false
    if (realFile is PyExpressionCodeFragment)
      return false

    // An injected fragment (`.. code-block:: python` in a docstring, Python inside a string literal, ...)
    // lives in a VirtualFileWindow over a DocumentWindow, which the LSP server never saw and which the
    // LSP coordinate API rejects outright. Notebooks are unaffected: Jupyter exposes its Python cells
    // through a template-language view provider over the real .ipynb document, not through injection.
    if (InjectedLanguageManager.getInstance(realFile.project).isInjectedFragment(realFile))
      return false

    // The server runs against the interpreter and the content roots of one module. A file of
    // another module must go to the server of that module, or it resolves to `Any`.
    // A library file belongs to no module, so any server can answer for it.
    val fileModule = ModuleUtilCore.findModuleForFile(realFile)
    if (fileModule != null && fileModule != module)
      return false

    val isSupportedTypesVisitor = LspIsSupportedTypesVisitor()
    pyTypedElement.accept(isSupportedTypesVisitor)
    return isSupportedTypesVisitor.isSupported
  }
}