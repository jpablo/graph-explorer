package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.Val
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.DefaultDiagramLanguages
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global

class SanitizationTest extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  given Owner = unsafeWindowOwner

  test("should sanitize leading newlines in labels"):
    withGraphvizAsync { graphviz =>
      val phases = new InternalPhases(DefaultDiagramLanguages(graphviz), hiddenNodes = Val(ElementIds()), pace = identity)

      val dotWithLeadingNewline =
        """digraph "G" {
          |  graph [
          |    label="\na\nb",
          |    lp="27,20.8"
          |  ];
          |  "b" [
          |    label="b"
          |  ];
          |  "a" [
          |    label="a"
          |  ];
          |  "a" -> "b" [
          |    label="f"
          |  ];
          |}""".stripMargin

      phases.sourceText.set(dotWithLeadingNewline)

      afterMicrotasks {
        val graph = phases.fullGraphV.now()

        // Check that the graph was parsed successfully
        assertEquals(graph.nodes.size, 2, "Should have exactly two nodes")
        assertEquals(graph.arrows.size, 1, "Should have exactly one arrow")

        // Check that the graph label has been sanitized
        val graphLabel = graph.elements.graphAttributes.get(Label.attrId)
        assert(graphLabel.isDefined, "Graph should have a label attribute")
        assertEquals(graphLabel.get.value, "a\\nb", "Graph label should have leading newline removed")
      }
    }

  test("should not modify labels without leading newlines"):
    withGraphvizAsync { graphviz =>
      val phases = new InternalPhases(DefaultDiagramLanguages(graphviz), hiddenNodes = Val(ElementIds()), pace = identity)

      val normalDot =
        """digraph "G" {
          |  graph [
          |    label="a\nb\nc"
          |  ];
          |  "a" [label="normal\nlabel"];
          |}""".stripMargin

      phases.sourceText.set(normalDot)

      afterMicrotasks {
        val graph = phases.fullGraphV.now()

        // Check that labels are unchanged
        val graphLabel = graph.elements.graphAttributes.get(Label.attrId)
        assertEquals(graphLabel.map(_.value), Some("a\\nb\\nc"), "Graph label should remain unchanged")

        val nodeA = graph.nodes(org.jpablo.graphexplorer.viewer.models.NodeId("a"))
        val nodeLabel = nodeA.attributes.get(Label.attrId)
        assertEquals(nodeLabel.map(_.value), Some("normal\\nlabel"), "Node label should remain unchanged")
      }
    }

  test("should handle multiple leading newlines"):
    withGraphvizAsync { graphviz =>
      val phases = new InternalPhases(DefaultDiagramLanguages(graphviz), hiddenNodes = Val(ElementIds()), pace = identity)

      val multipleNewlines =
        """digraph "G" {
          |  "a" [label="\n\n\ntest"];
          |}""".stripMargin

      phases.sourceText.set(multipleNewlines)

      afterMicrotasks {
        val graph = phases.fullGraphV.now()

        val nodeA = graph.nodes(org.jpablo.graphexplorer.viewer.models.NodeId("a"))
        val nodeLabel = nodeA.attributes.get(Label.attrId)
        assertEquals(nodeLabel.map(_.value), Some("test"), "All leading newlines should be removed")
      }
    }
