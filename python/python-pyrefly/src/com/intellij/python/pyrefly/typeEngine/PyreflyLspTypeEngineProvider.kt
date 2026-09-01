package com.intellij.python.pyrefly.typeEngine

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.registry.Registry
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.ensureClientStarted
import com.intellij.platform.lsp.api.getClients
import com.intellij.python.lsp.core.PyLspToolDescriptor
import com.intellij.python.lsp.core.typeEngine.PyTypeEngineUtils
import com.intellij.python.pyrefly.PyreflyPyTool
import com.intellij.python.pyrefly.lsp.PyreflyLspClientDescriptor
import com.intellij.python.pyrefly.lsp.PyreflyLspIntegrationProvider
import com.jetbrains.python.psi.types.engine.PyTypeEngine
import com.jetbrains.python.psi.types.engine.PyTypeEngineProvider


/**
 * External type engine provider that delegates to Pyrefly's LSP endpoint.
 * This provider is enabled when the Type Engine feature is enabled via registry.
 * The actual per-module check for Pyrefly configuration is done in [PyreflyLspTypeEngine.isSupportedForResolve].
 */
class PyreflyLspTypeEngineProvider : PyTypeEngineProvider {
  override fun createTypeEngine(module: Module): PyTypeEngine? {
    // Check if type engine feature is enabled (via registry or unit tests)
    val isFeatureEnabled = Util.isAvailable(module.project)

    if (!isFeatureEnabled) {
      return null
    }

    // Provide types only when Pyrefly is the selected type engine — not merely enabled as an LSP
    // tool. This decouples type inference from the External Tools toggle (PY-90550).
    if (!PyreflyPyTool.getInstance().isSelectedAsTypeEngine(module.project)) {
      return null
    }

    // Skip a module whose interpreter Pyrefly cannot drive: PyreflyLspClientDescriptor
    // .startServerProcess would throw. `isAvailable` above does not imply this, because the
    // `pyrefly.type.engine` key and unit-test mode both bypass the interpreter check.
    if (!PyTypeEngineUtils.isLocalNonReadOnlySdk(module)) {
      return null
    }

    val lspServerManager = LspClientManager.getInstance(module.project)
    lspServerManager.ensureClientStarted<PyreflyLspIntegrationProvider>(PyreflyLspClientDescriptor(module))
    // Take the client serving *this* module: with several servers running, the first one would
    // answer for another module's content roots and resolve everything to `Any`.
    val server = lspServerManager.getClients<PyreflyLspIntegrationProvider>()
                   .firstOrNull { (it.descriptor as? PyLspToolDescriptor)?.module == module }
                 ?: return null

    return PyreflyLspTypeEngine(module, server)
  }

  object Util {
    fun isAvailable(project: Project): Boolean {
      return (PyTypeEngineUtils.isExternalTypeEngineSupported(project) ||
              Registry.`is`("pyrefly.type.engine") ||
              ApplicationManager.getApplication().isUnitTestMode)
    }
  }
}
