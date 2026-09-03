// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.lsp.impl

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientDescriptor.Companion.LOG
import com.intellij.platform.lsp.api.LspIntegrationProvider
import org.jetbrains.annotations.ApiStatus
import org.jetbrains.jps.model.fileTypes.FileNameMatcherFactory

/**
 * Provides the default settings for one LSP server.
 *
 * The [serverId] must stay stable. The LSP UI uses it to store and retrieve the user configuration.
 */
@ApiStatus.Experimental
interface LspServerSettingsProvider {
  companion object {
    @JvmField
    val EP_NAME: ExtensionPointName<LspServerSettingsProvider> = ExtensionPointName.create("com.intellij.platform.lsp.serverSettingsProvider")
  }

  /** A stable identifier for this server. */
  val serverId: String

  /** The integration provider that starts this server. */
  val integrationProviderClass: Class<out LspIntegrationProvider>

  /** The default settings used when the user has not configured this server. */
  val defaultConfiguration: LspPluginServerConfiguration
}

/**
 * The configuration of an LSP server provided by a plugin.
 *
 * The LSP UI owns the state for these values.
 *
 * The class is immutable. To change a value, call [copy].
 *
 * @param name the display name of the server.
 * @param arguments command line arguments. Each list item is one argument.
 * @param filePatterns file name patterns. Use `*` for any number of characters and `?` for one character.
 * For example, `*.lua` matches all Lua files. A pattern matches the file name, not the full path.
 * @param initializationOptions a JSON object sent as the LSP `initialize` request options.
 * @param environmentVariables environment variables for the server process.
 */
@ApiStatus.Experimental
data class LspPluginServerConfiguration(
  @NlsSafe val name: String,
  val arguments: List<String> = emptyList(),
  val filePatterns: List<String> = emptyList(),
  val initializationOptions: String = "",
  val environmentVariables: EnvironmentVariablesData = EnvironmentVariablesData.DEFAULT,
) {
  /** Returns whether the file matches one of the configured file patterns. */
  fun isSupportedFile(file: VirtualFile): Boolean {
    return filePatterns.any { pattern ->
      pattern.isNotBlank() && FileNameMatcherFactory.getInstance().createMatcher(pattern).acceptsCharSequence(file.name)
    }
  }

  /** Returns the JSON initialization options for the LSP client, or null when the value is invalid or empty. */
  fun createInitializationOptions(): Any? {
    if (initializationOptions.isBlank()) {
      return null
    }

    return try {
      Gson().fromJson(initializationOptions, Map::class.java)
    }
    catch (e: JsonSyntaxException) {
      LOG.warn("Invalid JSON in initialization options for '${name}': ${e.message}")
      null
    }
  }
}
