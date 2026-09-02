// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.pytools.frontend.ui.configuration

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.util.SlowOperations
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Version as PlatformVersion
import com.intellij.platform.project.projectId
import com.intellij.python.pytools.common.PyToolApi
import com.intellij.python.pytools.common.PyToolPathKind
import com.intellij.python.pytools.common.PyToolPathRequest
import com.intellij.python.pytools.common.PyToolRequest
import com.intellij.python.pytools.common.PyToolSdkStateDto
import com.intellij.python.pytools.common.PyToolStateDto
import com.intellij.python.pytools.common.PyToolValidationDto
import com.intellij.python.pytools.frontend.PyToolFrontend as PyTool
import com.intellij.python.pytools.frontend.ui.PyToolsUiBundle
import com.intellij.python.pytools.frontend.ExternalPyToolFrontend as ExternalPyTool
import com.intellij.python.pytools.frontend.icons.PythonPytoolsFrontendIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.awt.Component
import javax.swing.Icon

/**
 * Snapshot of the user-editable per-row state.
 * The executable-discovery mode is no longer user-selectable — the page always runs the fixed
 * `SDK → Path → uvx` chain — so only the enable flag and the optional custom-path override are staged here.
 */
internal data class RowState(
  val enabled: Boolean,
  val customPath: String?,
)

internal class ToolRow(
  val tool: PyTool,
  var staged: RowState,
  var persistedEnabled: Boolean = false,
  var persistedCustomPath: String? = null,
  var detail: UnnamedConfigurable? = null,
  /** Non-null when the most recent validation of [staged].customPath failed. */
  var pathError: String? = null,
  /** Currently-running validation coroutine; cancelled on the next edit. */
  var validationJob: Job? = null,
  /** Version reported by `<path> --version` for [versionedFor]; null if probe is pending or failed. */
  var version: String? = null,
  /** Path for which [version] was probed. Used to skip re-probing the same binary on repaint. */
  var versionedFor: String? = null,
  /**
   * Currently-detected path snapshot, populated asynchronously. `null` means the initial detection
   * is still in flight (the cell renders empty until then) — the renderer must never call [detect]
   * itself, since `findInPath` does blocking disk I/O.
   */
  var pathFieldValue: PathFieldValue? = null,
  /**
   * Non-null when the resolved binary's version is below [PyTool.minimumSupportedVersion]. The
   * string is a short human-readable hint suitable for the path tooltip; the renderer also uses
   * its presence as a signal to switch the path text to an attention color.
   */
  var belowMinVersionMessage: String? = null,
  /**
   * True between the moment a uv install/upgrade is kicked off on this row and the moment the
   * modal closes. While set, the hover action-icon slot renders a spinner frame instead of the
   * regular install/upgrade icon, so the user sees that the click registered before the modal
   * comes up.
   */
  var actionInProgress: Boolean = false,
  /**
   * Set after a successful `uv tool install` / `uv tool upgrade` on this row to a short status
   * message (e.g. "ruff upgraded to 0.15.6"). While non-null the hover action icon switches to
   * a ✓ that, when hovered, surfaces this message — giving the user a quiet but visible cue
   * that the action did something. Cleared on next panel show via [PyExternalToolsList.onShown].
   */
  var lastSuccessMessage: String? = null,
  /**
   * Per-SDK detection result for the project's Python SDKs. `null` while the initial probe is
   * still in flight; non-null afterwards even when the project has no Python SDKs (the field
   * holds [SdkAvailability.NoProjectSdks] in that case).
   *
   * Surfaced in the Lookup column as a ✓ (all SDKs have it), ✗ (none have it), or ◐ (partial)
   * glyph next to `Sdk`, plus a tooltip listing the resolved binary path per SDK.
   */
  var sdkAvailability: SdkAvailability? = null,
  var canInstall: Boolean = false,
  var latestVersion: String? = null,
) {
  /** This tool's detail-panel provider, or `null` when the tool has no detail configurable. */
  val detailConfigurableProvider: ExternalPyTool? = tool as? ExternalPyTool
}

/**
 * Project-SDK detection snapshot for one [ToolRow]: an ordered list of SDKs with the tool's
 * resolved binary path inside each (or `null` when the tool isn't installed in that SDK).
 */
internal data class SdkAvailability(val entries: List<PyToolSdkStateDto>) {
  val totalCount: Int get() = entries.size
  val matchedCount: Int get() = entries.count { it.path != null }

  companion object {
    val NoProjectSdks: SdkAvailability = SdkAvailability(emptyList())
  }
}

internal sealed interface PathFieldValue {
  /** A user-supplied custom executable path (stored per Eel machine in `PyCustomExecutablePaths`). */
  data class Custom(val path: String) : PathFieldValue

  /** Path auto-detected on PATH or in a well-known per-user install directory. */
  data class AutoDetected(val path: String) : PathFieldValue

  /** Neither configured nor discoverable. */
  data object NotFound : PathFieldValue
}

/**
 * Resolve the row's displayed path. A user-supplied [customPath] wins; a [knownPath] (the exact path an
 * installer just reported) is trusted next; otherwise the tool is auto-detected via its own [PyExecutableCache],
 * which searches the tool's specific locations (e.g. conda's `~/miniconda3/bin`) — the same detection the interpreter
 * widget uses — so a tool installed outside `$PATH` is still found.
 */
/**
 * Right-edge action icon kinds for the Path column. After a successful install / upgrade the
 * renderer paints a ✓ in this slot instead, driven by [ToolRow.lastSuccessMessage]; the ✓ path
 * is not modeled here because it is purely a visual swap and uses no different hit-test.
 */
internal enum class PathIconKind(val icon: Icon?) {
  NONE(null),
  INSTALL(PythonPytoolsFrontendIcons.UI.Expui.Install),
  UPGRADE(PythonPytoolsFrontendIcons.UI.Expui.Upgrade),
  RESET(AllIcons.Diff.Revert),
}

/**
 * Compute the hover-only icon for a Path cell given the row's current state. The function is
 * deliberately pure: the caller supplies the "is an upgrade available" predicate, so the renderer
 * doesn't need to know how it is sourced.
 */
internal fun iconKindFor(
  toolRow: ToolRow?,
  detected: PathFieldValue?,
  canInstall: Boolean,
  isUpgradeAvailable: (ToolRow) -> Boolean,
): PathIconKind = when {
  toolRow == null -> PathIconKind.NONE
  // A manually-selected path overrides auto-detection entirely; the only meaningful hover
  // action there is "revert to auto-detection". Skip install / upgrade / info — none of them
  // apply to a user-pointed-at executable.
  detected is PathFieldValue.Custom -> PathIconKind.RESET
  // No installer for this tool on this target (a manager-less tool, or e.g. conda on a remote
  // interpreter): path-only, just the browse button. (Reset above still applies to a custom path.)
  !canInstall -> PathIconKind.NONE
  // Offer install for any undiscovered tool; the installer uses the tool's manager (uv/pip by default).
  detected is PathFieldValue.NotFound -> PathIconKind.INSTALL
  toolRow.version == null -> PathIconKind.NONE
  isUpgradeAvailable(toolRow) -> PathIconKind.UPGRADE
  // Otherwise no actionable icon — the path text + version tooltip already conveys the state.
  else -> PathIconKind.NONE
}

/**
 * Resolve the row's path (via [detect]) and then probe `<path> --version`, fully on background
 * coroutines. Both steps post their results back to the EDT, mutating the row in place and
 * invoking [onUpdated] (on EDT) so the caller can refresh whatever UI surface reads the row.
 *
 * Replaces any previously-running probe via [ToolRow.validationJob]. When [isCustomEdit] is
 * true, surface validation errors for the just-edited custom path via [ToolRow.pathError]; on
 * non-custom probes (initial detection, post-install refresh) the error is left untouched so a
 * transient failure of `<path> --version` doesn't ghost in as if the user mistyped the path.
 */
internal fun ToolRow.probeVersion(
  scope: CoroutineScope,
  project: Project,
  isCustomEdit: Boolean = false,
  onUpdated: (ToolRow) -> Unit,
) {
  validationJob?.cancel()
  val customPath = staged.customPath
  validationJob = scope.launch {
    if (isCustomEdit && customPath != null) {
      pathFieldValue = PathFieldValue.Custom(customPath)
      versionedFor = customPath
      when (val result = PyToolApi.getInstance().validatePath(
        PyToolPathRequest(PyToolRequest(project.projectId(), tool.toolId), customPath),
      )) {
        is PyToolValidationDto.Valid -> {
          pathError = null
          version = result.version
        }
        is PyToolValidationDto.Invalid -> {
          pathError = result.message
          version = null
        }
      }
      belowMinVersionMessage = computeBelowMinMessage(tool, version)
      onUpdated(this@probeVersion)
      return@launch
    }
    val state = PyToolApi.getInstance().getStates(
      com.intellij.python.pytools.common.PyToolsRequest(project.projectId(), listOf(tool.toolId)),
    ).singleOrNull() ?: return@launch
    applyBackendState(state, updateStagedPath = !isCustomEdit)
    onUpdated(this@probeVersion)
  }
}

internal fun ToolRow.applyBackendState(
  state: PyToolStateDto,
  updateStagedPath: Boolean = false,
  updateStagedEnabled: Boolean = false,
) {
  persistedEnabled = state.enabled
  if (updateStagedEnabled) staged = staged.copy(enabled = state.enabled)
  val customPath = state.path.takeIf { state.pathKind == PyToolPathKind.CUSTOM }
  persistedCustomPath = customPath
  if (updateStagedPath) staged = staged.copy(customPath = customPath)
  pathFieldValue = when (state.pathKind) {
    PyToolPathKind.CUSTOM -> state.path?.let(PathFieldValue::Custom)
    PyToolPathKind.DETECTED -> state.path?.let(PathFieldValue::AutoDetected)
    null -> null
  } ?: PathFieldValue.NotFound
  versionedFor = state.path
  version = state.version
  pathError = null
  belowMinVersionMessage = computeBelowMinMessage(tool, state.version)
  canInstall = state.canInstall
  latestVersion = state.latestVersion
}

/**
 * Returns a localized "Below minimum" hint when [version] is older than [PyTool.minimumSupportedVersion],
 * or `null` if the tool declares no minimum, the probe hasn't completed yet, or the version is fine.
 * The pytools [Version] is a string wrapper; parse it through the platform's comparable Version.
 */
private fun computeBelowMinMessage(tool: PyTool, version: String?): String? {
  val minimum = tool.minimumSupportedVersion ?: return null
  val actual = version?.let { PlatformVersion.parseVersion(it) } ?: return null
  if (actual >= minimum) return null
  return PyToolsUiBundle.message(
    "settings.external.tools.path.below.minimum.tooltip",
    tool.presentableName,
    formatVersion(minimum),
    formatVersion(actual),
  )
}

private fun formatVersion(v: PlatformVersion): String =
  if (v.bugfix > 0) "${v.major}.${v.minor}.${v.bugfix}" else "${v.major}.${v.minor}"

/**
 * Open a single-file picker preselected to the row's current path (custom or auto-detected),
 * and on confirmation hand the chosen path off to [onPathChosen]. The caller is responsible
 * for routing the result back into the row's `staged.customPath` (typically via the path
 * column's `setValueAt` so the standard cell-edit flow — re-probe, validation, repaint —
 * takes over).
 */
internal fun ToolRow.browseExecutablePath(
  project: Project,
  parent: Component?,
  onPathChosen: (String) -> Unit,
) {
  val descriptor = FileChooserDescriptorFactory.singleFile()
    .withTitle(PyToolsUiBundle.message("select.path.to.executable"))
  descriptor.isForcedToUseIdeaFileChooser = true
  // The IDEA chooser does synchronous VFS lookups (UniversalFileChooser.toVirtualFiles →
  // VfsUtil.findFile) inside its EDT-bound modal loop, which trips the slow-ops assertion.
  // The lookup is unavoidable for converting the picked file back to a VirtualFile, and the
  // chooser keeps the EDT busy by design, so wrap the call in a known-issue suppression.
  SlowOperations.knownIssue("PY-89945").use {
    FileChooser.chooseFile(descriptor, project, parent, null) { file ->
      onPathChosen(file.path)
    }
  }
}
