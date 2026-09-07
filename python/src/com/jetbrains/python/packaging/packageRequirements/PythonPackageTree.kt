// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.packaging.packageRequirements

import com.jetbrains.python.errorProcessing.PyResult
import com.jetbrains.python.packaging.PyPackageName
import com.jetbrains.python.packaging.common.PyDependencyGroupName
import com.jetbrains.python.packaging.common.PythonPackage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.annotations.ApiStatus
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Base type for all package tree nodes.
 */
@ApiStatus.Internal
sealed class PackageStructureNode

/**
 * Represents a single package with its transitive dependencies.
 *
 * One node per package: [TreeParser] gives every parent that needs a package the same node, so the
 * result is a graph, not a tree, and it can have a cycle. Equality is therefore identity, and
 * [toString] does not print the children. A caller that walks the graph keeps its own set of
 * visited nodes, by identity.
 */
@ApiStatus.Internal
class PackageTreeNode(
  val name: PyPackageName,
  val children: MutableList<PackageTreeNode> = mutableListOf(),
  val group: String? = null,
  val version: String? = null,
  /**
   * The extras the parent asked for, as written between the brackets, or `null` where it asked for
   * none. `pkg` and `pkg[extra]` have different dependencies, so they are different nodes with the
   * same [name], and only this tells them apart (PY-90174).
   */
  val extras: String? = null,
) {
  override fun equals(other: Any?): Boolean = this === other

  override fun hashCode(): Int = System.identityHashCode(this)

  override fun toString(): String = "PackageTreeNode(${name.name}, version=$version, children=${children.size})"
}

/**
 * Represents a workspace member with its sub-members and package dependency tree.
 *
 * @property name The name of the workspace member
 * @property subMembers List of nested workspace members (from pyproject.toml)
 * @property packageTree The dependency tree for this member's packages
 * @property undeclaredPackages Packages not declared in workspace but installed
 */
@ApiStatus.Internal
data class WorkspaceMemberPackageStructureNode(
  val name: String,
  val subMembers: List<WorkspaceMemberPackageStructureNode>,
  var packageTree: PackageTreeNode?,
  val undeclaredPackages: List<PackageTreeNode> = emptyList()
) : PackageStructureNode()

/**
 * Represents a flat collection of packages (non-workspace structure).
 *
 * @property declaredPackages Packages explicitly declared in project dependencies
 * @property undeclaredPackages Packages installed but not declared (transitive or manual)
 * @property projectPackageNames The project's own packages, which the view marks apart from a PyPI package
 */
@ApiStatus.Internal
data class PackageCollectionPackageStructureNode(
  val declaredPackages: List<PackageTreeNode>,
  val undeclaredPackages: List<PackageTreeNode>,
  val projectPackageNames: Set<String> = emptySet(),
) : PackageStructureNode()

/**
 * Indicates that all installed packages are considered declared (no tree structure available).
 * Used by simple package managers (e.g. pip) that don't distinguish declared from transitive.
 */
@ApiStatus.Internal
data object FlatPackageStructureNode : PackageStructureNode()

/**
 * Iteratively collects all package names from this node and its descendants.
 *
 * Tracks nodes, not names: a package requested through an extra is a second node with the same
 * name, and stopping at the first of them would drop whatever hangs below the other.
 */
@ApiStatus.Internal
fun PackageTreeNode.collectAllNames(): Set<String> {
  val result = mutableSetOf<String>()
  val visited = newNodeSet()
  val toVisit = ArrayDeque<PackageTreeNode>()
  toVisit.addLast(this)
  while (toVisit.isNotEmpty()) {
    val node = toVisit.removeLast()
    if (visited.add(node)) {
      result.add(node.name.name)
      toVisit.addAll(node.children)
    }
  }
  return result
}

/** A set that holds nodes by identity, for a walk over a graph that shares nodes and can cycle. */
@ApiStatus.Internal
fun newNodeSet(): MutableSet<PackageTreeNode> = Collections.newSetFromMap(IdentityHashMap())

@ApiStatus.Internal
object TreeParser {
  private data class ParseResult(
    val node: PackageTreeNode,
    val nextIndex: Int,
  )

  // Box-drawing characters used in tree output from package managers (uv, poetry, pip)
  private const val VERTICAL = '│'
  private const val BRANCH = '├'
  private const val CORNER = '└'
  private const val HORIZONTAL = '─'
  // ASCII fallbacks some tools use
  private const val VERTICAL_ASCII = '|'
  private const val CORNER_ASCII = '`'
  private const val HORIZONTAL_ASCII = '-'

  private val INDENT_PREFIXES = charArrayOf(' ', VERTICAL, BRANCH, CORNER, VERTICAL_ASCII, CORNER_ASCII)

  private val TREE_LINE_REGEX = Regex(
    """^[\s${VERTICAL}${VERTICAL_ASCII}${CORNER_ASCII}]*[${BRANCH}${CORNER}${CORNER_ASCII}${VERTICAL_ASCII}][${HORIZONTAL_ASCII}${HORIZONTAL}]+ """
  )
  private val GROUP_REGEX = Regex("""\((?:group|extra):\s*([\w.-]+)\)""")
  private const val SPACE_DELIMITER = ' '
  private const val VERSION_DELIMITER = '['

  /** Ends a line whose dependencies the tool printed under an earlier parent, or that closes a cycle. */
  private const val DEDUPE_MARKER = "(*)"

  /** The legend the tool adds once it deduplicated something, e.g. `(*) Package tree already displayed`. */
  private val LEGEND_LINE_REGEX = Regex("""^\(\*+\)\s""")

  fun parseTrees(lines: List<String>): List<PackageTreeNode> {
    val nonBlankLines = lines.withIndex().filterNot { it.value.isBlank() || LEGEND_LINE_REGEX.containsMatchIn(it.value) }
    val result = mutableListOf<PackageTreeNode>()
    // A package is parsed once. Every later line for it, the ones the tool marked, reuses that node.
    val nodesByPackage = mutableMapOf<String, PackageTreeNode>()
    var currentIndex = 0

    while (currentIndex < nonBlankLines.size) {
      val (originalIndex, line) = nonBlankLines[currentIndex]
      val (node, nextIndex) = parseLevel(lines, calculateIndentLevel(line), originalIndex, nodesByPackage)
      result.add(node)
      currentIndex = nonBlankLines.indexOfFirst { it.index >= nextIndex }.takeIf { it != -1 } ?: nonBlankLines.size
    }

    return result
  }

  fun isRootLine(line: String): Boolean {
    val first = line.firstOrNull() ?: return false
    return first !in INDENT_PREFIXES
  }

  private fun parseLevel(
    lines: List<String>,
    startIndent: Int,
    index: Int,
    nodesByPackage: MutableMap<String, PackageTreeNode>,
  ): ParseResult {
    val line = lines[index]
    // The key keeps the extras: `pkg` and `pkg[extra]` need different dependencies, so the tool
    // deduplicates them apart and so must this.
    val key = extractPackageKey(line)
    if (isDeduplicated(line)) {
      nodesByPackage[key]?.let { shown ->
        val group = extractGroup(line)
        // The group is why *this* parent needs the package, not a fact about the package, so a line that names a
        // different one keeps its own and shares only the dependencies. Sharing it too made a package that is only
        // ever declared through an extra look like a plain dependency, which is then reported as missing whenever
        // that extra is not installed (PY-90174).
        val node = if (group == shown.group) shown
        else PackageTreeNode(shown.name, shown.children, group, extractVersion(line))
        return ParseResult(node, index + 1)
      }
    }

    val node = PackageTreeNode(PyPackageName.from(extractPackageName(line)), mutableListOf(),
                               extractGroup(line), extractVersion(line), extractExtras(line))
    nodesByPackage.putIfAbsent(key, node)

    var currentIndex = index + 1
    while (currentIndex < lines.size && calculateIndentLevel(lines[currentIndex]) > startIndent) {
      val result = parseLevel(lines, calculateIndentLevel(lines[currentIndex]), currentIndex, nodesByPackage)
      node.children.add(result.node)
      currentIndex = result.nextIndex
    }
    return ParseResult(node, currentIndex)
  }

  private fun isDeduplicated(line: String): Boolean = line.trimEnd().endsWith(DEDUPE_MARKER)

  private fun extractExtras(line: String): String? {
    val key = extractPackageKey(line)
    if (!key.contains(VERSION_DELIMITER)) return null
    return key.substringAfter(VERSION_DELIMITER).substringBefore(']').takeIf { it.isNotBlank() }
  }

  private fun extractPackageKey(line: String): String =
    line.replaceFirst(TREE_LINE_REGEX, "").trimStart().split(SPACE_DELIMITER, limit = 2)[0]

  private fun calculateIndentLevel(line: String): Int {
    val indentMatch = TREE_LINE_REGEX.find(line)?.value ?: ""
    return indentMatch.length / 4
  }

  private fun extractPackageName(line: String): String {
    val clean = line.replaceFirst(TREE_LINE_REGEX, "").trimStart()
    return clean.split(SPACE_DELIMITER, limit = 2)[0]
      .substringBefore(VERSION_DELIMITER)
  }

  private fun extractGroup(line: String): String? {
    val groupMatch = GROUP_REGEX.find(line)
    return groupMatch?.groupValues?.get(1)
  }

  private fun extractVersion(line: String): String? {
    val clean = line.trimEnd().removeSuffix(DEDUPE_MARKER).trimEnd()
      .replaceFirst(TREE_LINE_REGEX, "").trimStart()
    // Skip the package name and optional bracketed extras (e.g., "pkg[cli, nats] v1.0")
    val afterName = clean.let {
      val bracketStart = it.indexOf(VERSION_DELIMITER)
      if (bracketStart >= 0) {
        val bracketEnd = it.indexOf(']', bracketStart)
        if (bracketEnd >= 0) it.substring(bracketEnd + 1).trimStart() else it.substringAfter(SPACE_DELIMITER, "")
      }
      else {
        it.substringAfter(SPACE_DELIMITER, "")
      }
    }
    return afterName
      .removePrefix("v")
      .substringBefore(' ')
      .takeIf { it.isNotBlank() }
  }
}

/**
 * Extracts declared (depth-1) dependencies from pre-parsed dependency trees.
 * Each root node's direct children represent a declared dependency.
 */
@ApiStatus.Internal
fun extractDeclaredDependencies(trees: List<PackageTreeNode>): List<PythonPackage> {
  return trees.flatMap { root ->
    root.children.map { child ->
      PythonPackage(
        child.name.name,
        child.version ?: "",
        false,
        child.group?.let { PyDependencyGroupName(it) }
      )
    }
  }.distinctBy { it.name }
}

@ApiStatus.Internal
interface DependencyTreeProvider {
  /**
   * A failure means the tree is unknown, not that the project declares nothing. A caller that
   * collapses the two reports every third-party import as undeclared (PY-90174).
   */
  suspend fun getDependencyTrees(): PyResult<List<PackageTreeNode>>
  fun invalidateCache()
}

@ApiStatus.Internal
internal class CachedDependencyTreeProvider(
  private val fetchOutput: suspend () -> PyResult<String>,
  private val parse: (String) -> List<PackageTreeNode> = { TreeParser.parseTrees(it.lines()) },
) : DependencyTreeProvider {
  private val mutex = Mutex()

  /** Only a successful fetch is cached, so the next read retries after a transient failure. */
  @Volatile
  private var cachedTrees: List<PackageTreeNode>? = null

  override suspend fun getDependencyTrees(): PyResult<List<PackageTreeNode>> {
    cachedTrees?.let { return PyResult.success(it) }
    return mutex.withLock {
      cachedTrees?.let { return PyResult.success(it) }
      fetchOutput().mapSuccess { output -> parse(output).also { cachedTrees = it } }
    }
  }

  override fun invalidateCache() {
    cachedTrees = null
  }
}
