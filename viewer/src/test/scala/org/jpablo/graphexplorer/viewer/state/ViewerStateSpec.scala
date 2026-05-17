package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BorderStyle, Color, FillColor, Shape}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{AttrStatus, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

class ViewerStateSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  test("addNodeWithSmartConnection should add a node to the graph"):
    withGraphvizAsync { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())

      afterMicrotasks {
        // sanity check
        assertEquals(viewerState.fullGraphNow(), ViewerGraph.minimal)
        assertEquals(viewerState.selection.size(), 0)

        viewerState.addNodeWithSmartConnection()

        // After this the recently added node is selected, so
        assertEquals(viewerState.selection.size(), 1)

        // ---- verify ---
        assertEquals(viewerState.allNodeIds().size, 1)
        assertEquals(viewerState.allArrowIds().size, 0)
      }
    }

  test("two consecutive addNodeWithSmartConnection should add two nodes and one arrow to the graph"):
    withGraphvizAsync { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())

      afterMicrotasks {
        // Initial state check
        assertEquals(viewerState.fullGraphNow(), ViewerGraph.minimal)

        viewerState.addNodeWithSmartConnection()
        // new node added and is currently selected
        viewerState.addNodeWithSmartConnection()
        // new node added and an arrow between the selected node and the new node

        // ---- verify ---
        assertEquals(viewerState.allNodeIds().size, 2)
        assertEquals(viewerState.allArrowIds().size, 1)
      }
    }

  test("addArrow should add an arrow to the graph"):
    withGraphvizAsync { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())

      afterMicrotasks {
        // Initial state check
        assertEquals(viewerState.fullGraphNow(), ViewerGraph.minimal)

        viewerState.addNodeWithSmartConnection()
        // clear selection to add just a node
        viewerState.selection.clear()
        viewerState.addNodeWithSmartConnection()
        val nodeIds = viewerState.allNodeIds().toSeq
        viewerState.addArrow(nodeIds.head, nodeIds.last)

        // ---- verify ---
        assertEquals(viewerState.allNodeIds().size, 2)
        assertEquals(viewerState.allArrowIds().size, 1)
      }
    }

  test("elementAttributes should update attributes for specific elements"):
    withGraphvizAsync { graphviz =>
      val viewerState = ViewerState(ProjectId("test"), graphviz, _ => ())

      afterMicrotasks {
        // Initial state check
        assertEquals(viewerState.fullGraphNow(), ViewerGraph.minimal)

        // Add two nodes to the graph
        viewerState.addNodeWithSmartConnection()
        viewerState.selection.clear()
        viewerState.addNodeWithSmartConnection()

        val Seq(nodeA, nodeB) = viewerState.allNodeIds().toSeq

        // Add an arrow between the nodes
        viewerState.addArrow(nodeA, nodeB)
        val Seq(arrowId) = viewerState.allArrowIds().toSeq

        // Test updating node attributes
        val nodeUpdates = viewerState.elementAttributesUpdates(ElementIds.from(nodeA))
        nodeUpdates.update(_ + (Color.attrId -> AttrValue("red")))

        // Verify node attributes are updated
        val updatedGraph = viewerState.fullGraphNow()
        assertEquals(
          updatedGraph.getAttributesById(nodeA).get(Color.attrId),
          Some(AttrValue("red")),
          "Node attributes should be updated"
        )

        // Test updating arrow attributes
        val arrowUpdates = viewerState.elementAttributesUpdates(ElementIds.from(arrowId))
        arrowUpdates.update(_ + (BorderStyle.attrId -> AttrValue("dotted")))

        // Verify arrow attributes are updated
        val updatedGraph2 = viewerState.fullGraphNow()
        assertEquals(
          updatedGraph2.getAttributesById(arrowId).get(BorderStyle.attrId),
          Some(AttrValue("dotted")),
          "Arrow attributes should be updated"
        )

        // Test updating multiple elements at once
        val multiUpdates = viewerState.elementAttributesUpdates(ElementIds(Set(nodeA, nodeB)))
        multiUpdates.update(_ + (Shape.attrId -> AttrValue("box")))

        // Verify multiple elements are updated
        val updatedGraph3 = viewerState.fullGraphNow()
        assertEquals(
          updatedGraph3.getAttributesById(nodeA).get(Shape.attrId),
          Some(AttrValue("box")),
          "First node shape should be updated"
        )
        assertEquals(
          updatedGraph3.getAttributesById(nodeB).get(Shape.attrId),
          Some(AttrValue("box")),
          "Second node shape should be updated"
        )
      }
    }

  // --- Signal pipeline tests ---
  // These tests verify that the reactive signal chain propagates correctly
  // from toolbar attribute edits all the way to SVG output.

  private def waitForCondition(
      condition:   => Boolean,
      description: String = "condition",
      timeoutMs:   Int = 5000,
      pollMs:      Int = 20
  ): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        p.tryFailure(new RuntimeException(s"Timed out after ${timeoutMs}ms waiting for $description"))
      else
        js.timers.setTimeout(pollMs)(loop())
    loop()
    p.future

  test("DOT svgWithPositions updates after attribute change via toolbar path"):
    withGraphvizAsync { graphviz =>
      val viewerState = ViewerState(ProjectId("dot-svg-test"), graphviz, _ => ())

      import com.raquo.laminar.api.L.unsafeWindowOwner
      given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

      afterMicrotasks {
        viewerState.addNodeWithSmartConnection()
        val Seq(nodeA) = viewerState.allNodeIds().toSeq

        val svgSignal = viewerState.svgWithPositions.observe
        val svgBefore = svgSignal.now()
        assert(svgBefore.isDefined, "DOT should produce an SVG")
        val svgHtmlBefore = svgBefore.get.svg.ref.outerHTML

        // Change fill color via the toolbar path (zoomLens on fullGraphV)
        val toolbarVar = viewerState.elementAttributesUpdates(ElementIds.from(nodeA))
        toolbarVar.update(_ + (FillColor.attrId -> AttrStatus.Single(AttrValue("red"))))

        val svgAfter = svgSignal.now()
        assert(svgAfter.isDefined, "DOT should still produce an SVG after edit")
        val svgHtmlAfter = svgAfter.get.svg.ref.outerHTML

        assertNotEquals(
          svgHtmlAfter,
          svgHtmlBefore,
          "svgWithPositions should produce different SVG after fill color change"
        )
        assert(
          svgHtmlAfter.contains("red"),
          s"Updated SVG should contain fill color 'red'. Got: ${svgHtmlAfter.take(300)}"
        )
      }
    }

  test("DOT finalSVG updates after attribute change via toolbar path"):
    withGraphvizAsync { graphviz =>
      val viewerState = ViewerState(ProjectId("dot-final-svg-test"), graphviz, _ => ())

      import com.raquo.laminar.api.L.unsafeWindowOwner
      given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

      afterMicrotasks {
        viewerState.addNodeWithSmartConnection()
        val Seq(nodeA) = viewerState.allNodeIds().toSeq

        val finalSvgSignal = viewerState.finalSVG.observe
        val svgBefore = finalSvgSignal.now()
        assert(svgBefore.isDefined, "DOT finalSVG should produce an SVG element")
        val htmlBefore = svgBefore.get.ref.outerHTML

        // Change fill color via the toolbar path
        val toolbarVar = viewerState.elementAttributesUpdates(ElementIds.from(nodeA))
        toolbarVar.update(_ + (FillColor.attrId -> AttrStatus.Single(AttrValue("red"))))

        val svgAfter = finalSvgSignal.now()
        assert(svgAfter.isDefined, "DOT finalSVG should still produce an SVG after edit")
        val htmlAfter = svgAfter.get.ref.outerHTML

        assertNotEquals(htmlAfter, htmlBefore, "finalSVG should change after fill color update")
        assert(htmlAfter.contains("red"), s"finalSVG should contain fill color 'red'. Got: ${htmlAfter.take(500)}")
      }
    }

  test("mermaid elementAttributesUpdates zoomed Var triggers sourceText signal update"):
    withGraphvizAsync { graphviz =>
      val mermaidSource =
        """flowchart LR
          |  A[CodeMirror]
          |  B[Parser]
          |  A --> B
          |classDef default fill:#fefecc,stroke:#85df72
          |""".stripMargin

      val viewerState = ViewerState(
        ProjectId("mermaid-test"),
        graphviz,
        initialSource = Some(mermaidSource)
      )

      // Track sourceText signal emissions to verify the SVG pipeline would fire
      import com.raquo.laminar.api.L.unsafeWindowOwner
      given com.raquo.airstream.ownership.Owner = unsafeWindowOwner
      val sourceTextEmissions = Var(List.empty[String])
      viewerState.sourceText.signal.foreach: text =>
        sourceTextEmissions.update(text :: _)

      for
        _ <- waitForCondition(
          condition = viewerState.fullGraphNow().nodes.nonEmpty,
          description = "mermaid initial parse",
          timeoutMs = 15000
        )
        _ <- afterMicrotasks {
          val graph = viewerState.fullGraphNow()
          assert(graph.nodes.contains(NodeId("B")), s"Parser node B should exist. Nodes: ${graph.nodes.keys}")
          // Clear emissions log to only track changes from the toolbar edit
          sourceTextEmissions.set(Nil)
        }
        _ <- afterMicrotasks {
          // Use the REAL toolbar path: elementAttributesUpdates(ids).set(...)
          val toolbarVar = viewerState.elementAttributesUpdates(ElementIds.from(NodeId("B")))
          val currentUpdates = toolbarVar.now()
          toolbarVar.set(currentUpdates + (FillColor.attrId -> AttrStatus.Single(AttrValue("#fb2c36"))))
        }
        _ <- waitForCondition(
          condition = viewerState.sourceText.now().contains("fill:#fb2c36"),
          description = "source text to contain fill style",
          timeoutMs = 5000
        )
        _ <- afterMicrotasks {
          val serialized = viewerState.sourceText.now()
          assert(serialized.contains("fill:#fb2c36"), s"Source text should include fill edit, got: $serialized")

          // Verify sourceText signal actually fired with the updated text.
          // This is what drives the Mermaid SVG rendering pipeline.
          val emissions = sourceTextEmissions.now()
          assert(
            emissions.exists(_.contains("fill:#fb2c36")),
            s"sourceText signal should have emitted the updated text (needed for SVG re-render). Emissions: ${emissions.map(_.take(60))}"
          )
        }
      yield ()
    }

  test("mermaid svgWithPositions fires after attribute change via toolbar path"):
    withGraphvizAsync { graphviz =>
      val mermaidSource =
        """flowchart LR
          |  A[CodeMirror]
          |  B[Parser]
          |  A --> B
          |classDef default fill:#fefecc,stroke:#85df72
          |""".stripMargin

      val viewerState = ViewerState(
        ProjectId("mermaid-svg-signal-test"),
        graphviz,
        initialSource = Some(mermaidSource),
        logLevel = org.jpablo.graphexplorer.viewer.logging.Level.Info
      )

      import com.raquo.laminar.api.L.unsafeWindowOwner
      given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

      // Track all svgWithPositions emissions
      val svgEmissions = Var(0)
      viewerState.svgWithPositions.foreach: _ =>
        svgEmissions.update(_ + 1)

      for
        _ <- waitForCondition(
          condition = viewerState.fullGraphNow().nodes.nonEmpty,
          description = "mermaid initial parse",
          timeoutMs = 15000
        )
        _ <- afterMicrotasks {
          // Record emission count before the edit
          val countBefore = svgEmissions.now()

          // Change fill color via the toolbar path (zoomLens on fullGraphV)
          val toolbarVar = viewerState.elementAttributesUpdates(ElementIds.from(NodeId("B")))
          val currentUpdates = toolbarVar.now()
          toolbarVar.set(currentUpdates + (FillColor.attrId -> AttrStatus.Single(AttrValue("#fb2c36"))))
        }
        _ <- waitForCondition(
          condition = viewerState.sourceText.now().contains("fill:#fb2c36"),
          description = "source text to contain fill style",
          timeoutMs = 5000
        )
        // Wait for the Mermaid render to fire
        _ <- waitForCondition(
          condition = svgEmissions.now() >= 2,
          description = "svgWithPositions to fire after edit (at least 2 emissions: initial + after edit)",
          timeoutMs = 5000
        )
        _ <- afterMicrotasks {
          assert(
            svgEmissions.now() >= 2,
            s"svgWithPositions should have fired at least twice (initial + after edit). Got ${svgEmissions.now()} emissions."
          )
        }
      yield ()
    }

  test("mermaid finalSVG updates after attribute change via toolbar path"):
    withGraphvizAsync { graphviz =>
      val mermaidSource =
        """flowchart LR
          |  A[CodeMirror]
          |  B[Parser]
          |  A --> B
          |classDef default fill:#fefecc,stroke:#85df72
          |""".stripMargin

      val viewerState = ViewerState(
        ProjectId("mermaid-final-svg-test"),
        graphviz,
        initialSource = Some(mermaidSource)
      )

      import com.raquo.laminar.api.L.unsafeWindowOwner
      given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

      val finalSvgSignal = viewerState.finalSVG.observe

      for
        _ <- waitForCondition(
          condition = viewerState.fullGraphNow().nodes.nonEmpty,
          description = "mermaid initial parse",
          timeoutMs = 15000
        )
        _ <- waitForCondition(
          condition = finalSvgSignal.now().isDefined,
          description = "initial finalSVG to be defined",
          timeoutMs = 5000
        )
        _ <- afterMicrotasks {
          val svgBefore = finalSvgSignal.now()
          assert(svgBefore.isDefined, "Mermaid finalSVG should produce an SVG element")
        }
        _ <- afterMicrotasks {
          // Change fill color via the toolbar path
          val toolbarVar = viewerState.elementAttributesUpdates(ElementIds.from(NodeId("B")))
          val currentUpdates = toolbarVar.now()
          toolbarVar.set(currentUpdates + (FillColor.attrId -> AttrStatus.Single(AttrValue("#fb2c36"))))
        }
        _ <- waitForCondition(
          condition = viewerState.sourceText.now().contains("fill:#fb2c36"),
          description = "source text to contain fill style",
          timeoutMs = 5000
        )
        _ <- waitForCondition(
          condition = finalSvgSignal.now().exists(_.ref.outerHTML.contains("fb2c36")),
          description = "finalSVG to contain updated fill color",
          timeoutMs = 5000
        )
        _ <- afterMicrotasks {
          val svgAfter = finalSvgSignal.now()
          assert(svgAfter.isDefined, "Mermaid finalSVG should still be defined after edit")
          val html = svgAfter.get.ref.outerHTML
          assert(html.contains("fb2c36"), s"Mermaid finalSVG should contain fill color '#fb2c36'. Got: ${html.take(500)}")
        }
      yield ()
    }

  test("mermaid elementAttributesUpdates: source text remains stable after re-parse settles"):
    withGraphvizAsync { graphviz =>
      val mermaidSource =
        """flowchart LR
          |  A[CodeMirror]
          |  B[Parser]
          |  A --> B
          |classDef default fill:#fefecc,stroke:#85df72
          |""".stripMargin

      val viewerState = ViewerState(
        ProjectId("mermaid-stable-test"),
        graphviz,
        initialSource = Some(mermaidSource)
      )

      import com.raquo.airstream.ownership.Owner
      import com.raquo.laminar.api.L.unsafeWindowOwner
      given Owner = unsafeWindowOwner

      for
        _ <- waitForCondition(
          condition = viewerState.fullGraphNow().nodes.nonEmpty,
          description = "mermaid initial parse",
          timeoutMs = 15000
        )
        _ <- afterMicrotasks {
          val toolbarVar = viewerState.elementAttributesUpdates(ElementIds.from(NodeId("B")))
          val currentUpdates = toolbarVar.now()
          toolbarVar.set(currentUpdates + (FillColor.attrId -> AttrStatus.Single(AttrValue("#fb2c36"))))
        }
        _ <- waitForCondition(
          condition = viewerState.sourceText.now().contains("fill:#fb2c36"),
          description = "source text to contain fill style",
          timeoutMs = 5000
        )
        _ <- afterMicrotasks {
          val textAfterEdit = viewerState.sourceText.now()
          assert(textAfterEdit.contains("fill:#fb2c36"), s"Source should have fill right after edit: $textAfterEdit")
        }
        // Wait extra time for any deferred re-parse to settle
        _ <- waitForCondition(
          condition = true, // just a delay
          description = "settle delay",
          timeoutMs = 100,
          pollMs = 100
        )
        _ <- afterMicrotasks {
          val textAfterSettle = viewerState.sourceText.now()
          assert(
            textAfterSettle.contains("fill:#fb2c36"),
            s"Source text should STILL contain fill:#fb2c36 after re-parse settles. " +
              s"If this fails, a re-parse stripped the fill attribute. Got: $textAfterSettle"
          )
        }
      yield ()
    }

  test("mermaid per-node style directive should override classDef default in toolbar attributes"):
    withGraphvizAsync { graphviz =>
      val mermaidSource =
        """flowchart LR
          |  classDef default fill:#fefecc,stroke:#85df72
          |  A[CodeMirror]
          |  B[Parser]
          |  A --> B
          |  style B fill:#1c398e,stroke:#85df72
          |""".stripMargin

      val viewerState = ViewerState(
        ProjectId("mermaid-style-override-test"),
        graphviz,
        initialSource = Some(mermaidSource)
      )

      import com.raquo.laminar.api.L.unsafeWindowOwner
      given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

      for
        _ <- waitForCondition(
          condition = viewerState.fullGraphNow().nodes.nonEmpty,
          description = "mermaid initial parse",
          timeoutMs = 15000
        )
        _ <- afterMicrotasks {
          val graph = viewerState.fullGraphNow()
          assert(graph.nodes.contains(NodeId("B")), s"Parser node B should exist. Nodes: ${graph.nodes.keys}")

          // Node B has 'style B fill:#1c398e' — this should override 'classDef default fill:#fefecc'
          val toolbarB = viewerState.elementAttributesUpdates(ElementIds.from(NodeId("B"))).now()
          val fillB = toolbarB.statuses.get(FillColor.attrId)
          assert(
            fillB.exists(_.is(AttrValue("#1c398e"))),
            s"Node B fill should be #1c398e (from per-node 'style B'), not classDef default #fefecc. Got: $fillB"
          )

          // Node A has no per-node style — should get classDef default fill
          val toolbarA = viewerState.elementAttributesUpdates(ElementIds.from(NodeId("A"))).now()
          val fillA = toolbarA.statuses.get(FillColor.attrId)
          assert(
            fillA.exists(_.is(AttrValue("#fefecc"))),
            s"Node A fill should be #fefecc (from classDef default). Got: $fillA"
          )
        }
      yield ()
    }
