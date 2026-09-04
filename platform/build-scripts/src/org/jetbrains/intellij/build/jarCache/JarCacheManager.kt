// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
@file:Suppress("ReplaceGetOrSet", "ReplacePutWithAssignment")

package org.jetbrains.intellij.build.jarCache

import com.dynatrace.hash4j.hashing.HashStream64
import io.opentelemetry.api.trace.Span
import org.jetbrains.intellij.build.Source
import org.jetbrains.intellij.build.ZipSource
import java.nio.file.Path

interface SourceBuilder {
  val useCacheAsTargetFile: Boolean

  fun updateDigest(digest: HashStream64)

  fun produce(targetFile: Path)

  fun consumeInfo(source: Source, size: Int, hash: Long)
}

sealed interface JarCacheManager {
  fun computeIfAbsent(
    sources: Collection<Source>,
    targetFile: Path,
    nativeFiles: MutableMap<ZipSource, List<String>>?,
    span: Span,
    producer: SourceBuilder,
  ): Path

  fun cleanup()
}

internal data object NonCachingJarCacheManager : JarCacheManager {
  override fun computeIfAbsent(
    sources: Collection<Source>,
    targetFile: Path,
    nativeFiles: MutableMap<ZipSource, List<String>>?,
    span: Span,
    producer: SourceBuilder,
  ): Path {
    producer.produce(targetFile)
    return targetFile
  }

  override fun cleanup() {
  }
}
