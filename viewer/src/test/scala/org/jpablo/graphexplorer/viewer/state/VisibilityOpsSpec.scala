package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{ArrowId, NodeId}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

class VisibilityOpsSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  private def buildGraph(state: ViewerState, nodes: Seq[String], edges: Seq[(String, String)]): Unit =
    state.phases.fullGraphV.update { g0 =>
      val withNodes = nodes.foldLeft(g0) { case (acc, id) =>
        acc.addNodeWithId(NodeId(id))
      }
      val withArrows = edges.foldLeft(withNodes) { case (acc, (s, t)) =>
        acc.addArrow(NodeId(s), NodeId(t))._1
      }
      withArrows
    }

  private def findArrowId(g: ViewerGraph, s: String, t: String): ArrowId =
    g.arrows.values.find(a => a.source.value == s && a.target.value == t).get.id

  test("hideSuccessorLayer hides only the immediate layer and nodes that lose all incoming"):
    withGraphviz { graphviz =>
      val st = ViewerState(ProjectId("test"), graphviz, _ => ())

      // Graph: A->B, E->B, A->D, D->F, B->G
      buildGraph(st, nodes = Seq("A", "B", "D", "E", "F", "G"), edges = Seq(
        "A" -> "B",
        "E" -> "B",
        "A" -> "D",
        "D" -> "F",
        "B" -> "G"
      ))

      st.selection.set1(Set(NodeId("A")))
      st.hideSuccessors(recursive = false)

      val g        = st.fullGraphNow()
      val hidden   = st.hiddenElements.now().classify
      val aToB     = findArrowId(g, "A", "B")
      val aToD     = findArrowId(g, "A", "D")
      val dToF     = findArrowId(g, "D", "F")

      assert(hidden.arrows.contains(aToB), "A->B arrow should be hidden")
      assert(hidden.arrows.contains(aToD), "A->D arrow should be hidden")
      assert(!hidden.arrows.contains(dToF), "D->F arrow should remain visible in non-recursive mode")

      assert(hidden.nodes.contains(NodeId("D")), "D should be hidden (no other incoming)")
      assert(!hidden.nodes.contains(NodeId("B")), "B should remain visible (has E->B)")
    }

  test("hideSuccessorsRecursive hides deeper layers transitively"):
    withGraphviz { graphviz =>
      val st = ViewerState(ProjectId("test"), graphviz, _ => ())

      // Graph: A->B, E->B, A->D, D->F, B->G
      buildGraph(st, nodes = Seq("A", "B", "D", "E", "F", "G"), edges = Seq(
        "A" -> "B",
        "E" -> "B",
        "A" -> "D",
        "D" -> "F",
        "B" -> "G"
      ))

      st.selection.set1(Set(NodeId("A")))
      st.hideSuccessors(recursive = true)

      val g        = st.fullGraphNow()
      val hidden   = st.hiddenElements.now().classify
      val aToB     = findArrowId(g, "A", "B")
      val aToD     = findArrowId(g, "A", "D")
      val dToF     = findArrowId(g, "D", "F")

      assert(hidden.arrows.contains(aToB), "A->B arrow should be hidden")
      assert(hidden.arrows.contains(aToD), "A->D arrow should be hidden")
      assert(hidden.arrows.contains(dToF), "D->F arrow should be hidden recursively")

      assert(hidden.nodes.contains(NodeId("D")), "D should be hidden (no other incoming)")
      assert(hidden.nodes.contains(NodeId("F")), "F should be hidden transitively")
      assert(!hidden.nodes.contains(NodeId("B")), "B should remain visible (has E->B)")
    }

  test("deleteHiddenElements removes hidden elements from the graph and clears hidden state"):
    withGraphviz { graphviz =>
      val st = ViewerState(ProjectId("test"), graphviz, _ => ())

      // Stub confirm to always approve
      js.eval(
        """
          if (typeof window === 'undefined') { global.window = {}; }
          window.confirm = function(_) { return true; };
        """
      )

      // Graph: A->D, D->F
      buildGraph(st, nodes = Seq("A", "D", "F"), edges = Seq(
        "A" -> "D",
        "D" -> "F"
      ))

      st.selection.set1(Set(NodeId("A")))
      st.hideSuccessors(recursive = true)

      val hiddenBefore = st.hiddenElements.now().classify
      assert(hiddenBefore.nodes.contains(NodeId("D")))
      assert(hiddenBefore.nodes.contains(NodeId("F")))

      st.deleteHiddenElements()

      val gAfter     = st.fullGraphNow()
      val hiddenAfter = st.hiddenElements.now()

      assert(!gAfter.nodeIds.contains(NodeId("D")))
      assert(!gAfter.nodeIds.contains(NodeId("F")))
      assert(hiddenAfter.isEmpty, "Hidden set should be cleared after deletion")
    }

  test("hideSuccessorsRecursive handles edges with source ports (e:\"s\" -> d)"):
    withGraphviz { graphviz =>
      val dot =
        """
          |digraph "G" {
          |  graph [label=""];
          |  subgraph "g2" {
          |    graph [
          |      label="ViewerGraphElements",
          |      cluster="true"
          |    ];
          |    "d" [label="combineStyleAttributes"];
          |  }
          |  subgraph "g5" {
          |    graph [
          |      label="ViewerGraph",
          |      cluster="true"
          |    ];
          |    "e" [label="viewerGraphToText"];
          |  }
          |  subgraph "g6" {
          |    graph [
          |      label="viewer.graph",
          |      cluster="true"
          |    ];
          |    "p" [label="viewerGraphElementsToText"];
          |  }
          |  "e" -> "p";
          |  "e":"s" -> "d" [constraint="true"];
          |}
          |""".stripMargin

      val st = ViewerState(ProjectId("test"), graphviz, _ => (), initialSource = Some(dot))

      st.selection.set1(Set(NodeId("e")))
      st.hideSuccessors(recursive = true)

      val hidden = st.hiddenElements.now().classify
      // Before the fix this would fail: only p was being hidden
      assert(hidden.nodes.contains(NodeId("d")), "Node d should be hidden when only reachable through e:s->d")
      assert(hidden.nodes.contains(NodeId("p")), "Node p should be hidden (direct successor)")
    }
