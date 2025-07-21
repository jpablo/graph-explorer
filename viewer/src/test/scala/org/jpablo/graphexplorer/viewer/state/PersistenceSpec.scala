package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.AttributeId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global

class PersistenceSpec extends FunSuite with TestHelpers:
  // Graphviz adds a default directed attribute
  val minimalWithDirected =
    ViewerGraph.minimal.modify(_.elements.graphAttributes).using(_ + (AttributeId("directed") -> AttrValue("true")))

  override def munitFixtures = List(mockStorageFixture())


  def storedProjectKey(projectId: ProjectId): String =
    s"[StoredString]graph-explorer.project.${projectId.value}"

  test("Adding a node with smart connection should store the project in localStorage") {
    withGraphviz { graphviz =>

      val projectName = "my project"
      val projectId   = ProjectId("test")
      val viewerState = ViewerState(projectId, graphviz)
      // sanity check
      assertEquals(viewerState.fullGraphNow(), minimalWithDirected)

      assertEquals(dom.window.localStorage.length, 0)

      viewerState.project.name.set(projectName)
      viewerState.addNodeWithSmartConnection()

      assertEquals(
        obtained = dom.window.localStorage.length,
        expected = 2,
        "Should have two items in localStorage: one for the project and one for the graph explorer version"
      )

      val storedProjectStr = dom.window.localStorage.getItem(storedProjectKey(projectId))

      assertEquals(
        storedProjectStr,
        s"""{"projectName":"$projectName","source":"digraph \\"G\\" {\\n  \\"a\\" [label=\\"\\"];\\n}"}"""
      )

      // ---- verify ---
      assertEquals(viewerState.allNodeIds().size, 1)
      assertEquals(viewerState.allArrowIds().size, 0)
    }
  }
