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

  test("Sanity check") {
    withGraphviz { graphviz =>

      val viewerState = ViewerState(ProjectId("test"), graphviz)
      // sanity check
      assertEquals(viewerState.fullGraphNow(), minimalWithDirected)

      dom.console.log(dom.window.localStorage.length)

      viewerState.addNodeWithSmartConnection()

      dom.console.log(dom.window.localStorage.key(0))
      dom.console.log(dom.window.localStorage.key(1))

      // After this the recently added node is selected, so
      assertEquals(viewerState.selection.size(), 1)

      // ---- verify ---
      assertEquals(viewerState.allNodeIds().size, 1)
      assertEquals(viewerState.allArrowIds().size, 0)

    }
  }
