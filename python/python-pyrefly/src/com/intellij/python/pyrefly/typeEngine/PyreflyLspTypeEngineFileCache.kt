package com.intellij.python.pyrefly.typeEngine

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.LowMemoryWatcher
import com.intellij.openapi.util.LowMemoryWatcher.LowMemoryWatcherType
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.platform.lsp.api.LspClient
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.python.lsp.core.type.LspTypeEvalContext
import com.intellij.python.lsp.core.utils.PyLspServerModificationTracker
import com.intellij.util.containers.ContainerUtil
import com.jetbrains.python.psi.types.PyLibraryModificationTracker
import java.util.concurrent.ConcurrentMap

/**
 * Pyrefly contains own cache for resolved types,
 * and we need to rely on it not on our psi tracker
 * as we do for the built-in resolver
 */
@Service(Service.Level.PROJECT)
internal class PyreflyLspTypeEngineFileCache(val project: Project) : Disposable.Default {
  private val lowMemoryModificationTracker = SimpleModificationTracker()


  private val cachedMapStorage = CachedValuesManager.getManager(project).createCachedValue {
    val map = ContainerUtil.createConcurrentSoftValueMap<ContextKey, LspTypeEvalContext>()
    CachedValueProvider.Result.create(map,
                                      PsiModificationTracker.getInstance(project ),
                                      lowMemoryModificationTracker,
                                      PyLspServerModificationTracker.getInstance(project))
  }

  private val librariesCachedMapStorage: CachedValue<ConcurrentMap<ContextKey, LspTypeEvalContext>> =
    CachedValuesManager.getManager(project).createCachedValue {
      val map = ContainerUtil.createConcurrentSoftValueMap<ContextKey, LspTypeEvalContext>()
      CachedValueProvider.Result.create(map,
                                        PyLibraryModificationTracker.getInstance(project),
                                        lowMemoryModificationTracker,
                                        PyLspServerModificationTracker.getInstance(project))
    }


  init {
    LowMemoryWatcher.register(
      {
        lowMemoryModificationTracker.incModificationCount()
        cachedMapStorage.value
        librariesCachedMapStorage.value
      }, LowMemoryWatcherType.ALWAYS, this)
  }


  fun getContext(file: PsiFile, lspClient: LspClient, isLibrary: Boolean): LspTypeEvalContext {
    val storage = if (isLibrary) librariesCachedMapStorage else cachedMapStorage
    val cache = storage.value
    return cache.getOrPut(ContextKey(file, lspClient)) { PyreflyTypeEvalContext(lspClient, file) }
  }

  /**
   * The client is part of the key because a multi-module project runs one Pyrefly server per module.
   * With the file alone as the key, the first module to resolve a file would bind its
   * own client for every later requester, and the other modules would resolve to `Any`.
   */
  private data class ContextKey(val file: PsiFile, val lspClient: LspClient)

  companion object {
    fun getInstance(project: Project): PyreflyLspTypeEngineFileCache = project.service()
  }
}