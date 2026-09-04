// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.packaging.toolwindow.packages.tree

import com.jetbrains.python.allure.Layers
import com.jetbrains.python.allure.Subsystems
import com.jetbrains.python.packaging.common.PythonPackage
import com.jetbrains.python.packaging.repository.PyPiPackageRepository
import com.jetbrains.python.packaging.toolwindow.model.DisplayablePackage
import com.jetbrains.python.packaging.toolwindow.model.RequirementPackage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * The rows the packages view builds from a dependency graph that shares a package between its
 * dependents and can have a cycle (PY-90174).
 */
@Subsystems.PackagingRequirements
@Layers.Functional
class PyPackagesTreeRowsTest {

  @Test
  fun `a package keeps its dependencies wherever it appears`() {
    // `celery` is a dependency of two packages. Both rows must list what it depends on.
    val kombu = pkg("kombu")
    val celery = pkg("celery", kombu)
    val root = pkg("myapp", pkg("airflow", celery), pkg("flower", celery))

    val rows = root.toTreeNode()

    assertThat(rows.childNames()).containsExactly("airflow", "flower")
    for (parent in 0..1) {
      val celeryRow = rows.childAt(parent).childAt(0)
      assertThat(celeryRow.name()).isEqualTo("celery")
      assertThat(celeryRow.childNames()).describedAs("dependencies under parent $parent").containsExactly("kombu")
    }
  }

  @Test
  fun `a package repeating on its own path is a leaf`() {
    val a = pkg("a")
    val b = pkg("b", a)
    a.dependsOn(b)

    val rows = a.toTreeNode()

    // a -> b -> a, and there the walk ends.
    val repeated = rows.childAt(0).childAt(0)
    assertThat(repeated.name()).isEqualTo("a")
    assertThat(repeated.childCount).isEqualTo(0)
  }

  @Test
  fun `a self dependency is a leaf`() {
    val a = pkg("a")
    a.dependsOn(a)

    val rows = a.toTreeNode()

    assertThat(rows.childAt(0).name()).isEqualTo("a")
    assertThat(rows.childAt(0).childCount).isEqualTo(0)
  }

  private fun pkg(name: String, vararg requirements: RequirementPackage): RequirementPackage =
    RequirementPackage(PythonPackage(name, "1.0", false), PyPiPackageRepository, mutableListOf(*requirements))

  /** The graph can have a cycle, which only a mutable dependency list can express. */
  private fun RequirementPackage.dependsOn(other: RequirementPackage) {
    @Suppress("UNCHECKED_CAST")
    (getRequirements() as MutableList<RequirementPackage>).add(other)
  }

  private fun DefaultMutableTreeNode.childAt(index: Int) = getChildAt(index) as DefaultMutableTreeNode
  private fun DefaultMutableTreeNode.name() = (userObject as DisplayablePackage).name
  private fun DefaultMutableTreeNode.childNames() = children().toList().map { (it as DefaultMutableTreeNode).name() }
}

/**
 * [expandAllRows] must reach the rows that expanding itself adds, which a bound taken before the
 * first expansion does not (PY-90174).
 */
@Subsystems.PackagingRequirements
@Layers.Functional
class PyPackagesTreeExpandTest {

  @Test
  fun `every row is expanded, including the ones expanding adds`() {
    val tree = JTree(chain())
    tree.isRootVisible = true
    tree.collapseRow(0)

    tree.expandAllRows()

    // A chain of five: every node is visible only once every row above it is expanded.
    assertThat(tree.rowCount).isEqualTo(CHAIN_DEPTH)
  }

  @Test
  fun `a wide tree is expanded on every branch`() {
    val root = DefaultMutableTreeNode("root")
    repeat(3) { branch ->
      val node = DefaultMutableTreeNode("branch-$branch")
      node.add(DefaultMutableTreeNode("leaf-$branch"))
      root.add(node)
    }
    val tree = JTree(root)
    tree.isRootVisible = true

    tree.expandAllRows()

    assertThat(tree.rowCount).isEqualTo(7)
  }

  /** Five nodes, each the only child of the one above, so each row appears only after the last is expanded. */
  private fun chain(): DefaultMutableTreeNode {
    val root = DefaultMutableTreeNode("node-0")
    var current = root
    for (level in 1 until CHAIN_DEPTH) {
      val child = DefaultMutableTreeNode("node-$level")
      current.add(child)
      current = child
    }
    return root
  }

  private companion object {
    private const val CHAIN_DEPTH: Int = 5
  }
}
