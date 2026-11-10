package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.*
import org.jpablo.graphexplorer.viewer.components.selection.{ClusterElement, EdgeElement, NodeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Rankdir, Style}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{AttributeId, Attributes, GroupId, NodeId}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** A state diagram survives the printer, with the real Mermaid parser.
  *
  * A canvas edit replaces the source with the printed graph, and the viewer keeps the edited graph. It does not parse the new text. Thus
  * the printed text must parse into the same graph, and its drawing must have the ids of that graph, or the canvas cannot select what it
  * shows.
  *
  * The checks throw AssertionError, not munit's `assert`: through the Scala.js test bridge and the sbt client, a munit FailException
  * arrives with no message.
  */
class StateDiagramRoundTripSpec extends FunSuite:

  private val Samples = List(
    "editor" ->
      """stateDiagram-v2
        |  [*] --> Idle
        |  Idle --> Selecting : click
        |  Selecting --> Editing : Enter
        |  Editing --> Selecting : save
        |  Selecting --> Idle : Escape
        |  Selecting --> Selecting : s / shift+S
        |  Editing --> [*] : close
        |""".stripMargin,
    "composite" ->
      """stateDiagram-v2
        |  [*] --> Active
        |  state Active {
        |    [*] --> Typing
        |    Typing --> Paused : pause
        |    Paused --> Typing : resume
        |  }
        |  Active --> Done : quit
        |  Done --> [*]
        |""".stripMargin,
    "everything the model keeps" ->
      """---
        |title: Checkout
        |---
        |stateDiagram-v2
        |  direction LR
        |  classDef bad fill:#f00,color:white
        |  state "Waiting #58; for input" as Idle
        |  Idle : first line
        |  Idle : second line
        |  state F <<fork>>
        |  state J <<join>>
        |  state C <<choice>>
        |  [*] --> Idle
        |  Idle --> F : go #59; now
        |  F --> Pay
        |  F --> Ship
        |  Pay --> J
        |  Ship --> J
        |  J --> C
        |  C --> Done : ok
        |  C --> Idle : retry
        |  state "Outer box" as Outer {
        |    [*] --> Inner
        |    state Inner {
        |      [*] --> X
        |      X --> [*]
        |    }
        |    Inner --> Y : leave
        |  }
        |  Done --> Outer
        |  Y:::bad --> Done
        |  Done --> Done
        |  Pay --> Ship : twice
        |  Pay --> Ship : twice again
        |  class Pay bad
        |  style Ship fill:#0f0
        |  Done --> [*]
        |""".stripMargin
  )

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then throw AssertionError(message)

  private def parse(text: String): Future[ViewerGraph] =
    MermaidBackend().textToGraph(text).recover { case e =>
      throw AssertionError(s"the text did not parse into a graph: ${e.getMessage}\n$text")
    }

  /** The graph, with no DOM ids: the printer writes the blocks in a new order, and Mermaid numbers the transitions in the order of the
    * text.
    */
  private def comparable(graph: ViewerGraph) =
    (
      graph.nodes.toMap,
      graph.arrows.view.mapValues(_.modifyAttrs.using(a =>
        Attributes.fromOrdered(a.values.toSeq.filterNot(_._1.equals(MermaidDomIdAttr)))
      )).toMap,
      graph.groups,
      graph.memberships.toMap,
      graph.elements.graphAttributes
    )

  private def drawnIds(text: String, graph: ViewerGraph): Future[Unit] =
    val backend = MermaidBackend()
    backend.textToSvg(text).map: rendered =>
      val drawn       = SelectableElement.findAll(rendered.svg.ref, backend.selectionStrategy)
      val drawnNodes  = drawn.collect { case n: NodeElement => n.elementId }.toSet
      val drawnArrows = drawn.collect { case e: EdgeElement => e.elementId }.toSet
      val drawnGroups = drawn.collect { case c: ClusterElement => c.elementId }.toSet
      // A composite state that a transition reaches is also a node, but it is
      // drawn as a group.
      val nodes = graph.nodeIds.toSet.filterNot(n => graph.groupIds.exists(_.value == n.value))
      check(drawnNodes == nodes, s"drawn states $drawnNodes, graph states $nodes\n$text")
      check(drawnArrows == graph.arrowIds.toSet, s"drawn transitions $drawnArrows, graph transitions ${graph.arrowIds.toSet}\n$text")
      check(drawnGroups == graph.groupIds.toSet, s"drawn composite states $drawnGroups, graph groups ${graph.groupIds.toSet}\n$text")

  for (name, source) <- Samples do
    test(s"$name: the printed text parses into the same graph"):
      for
        before <- parse(source)
        printed = MermaidBackend().graphToText(before, omitInternal = true)
        after <- parse(printed)
      yield
        val (n1, a1, g1, m1, ga1) = comparable(before)
        val (n2, a2, g2, m2, ga2) = comparable(after)
        check(n1.equals(n2), s"the nodes changed:\n${n1.toSet -- n2.toSet}\n${n2.toSet -- n1.toSet}\n$printed")
        check(a1.equals(a2), s"the arrows changed:\n${a1.toSet -- a2.toSet}\n${a2.toSet -- a1.toSet}\n$printed")
        check(g1.equals(g2), s"the groups changed:\n$g1\n$g2\n$printed")
        check(m1.equals(m2), s"the memberships changed:\n$m1\n$m2\n$printed")
        check(ga1.equals(ga2), s"the graph attributes changed:\n$ga1\n$ga2\n$printed")

    test(s"$name: the drawing of an edited graph has the ids of the graph"):
      for
        before <- parse(source)
        _      <- drawnIds(source, before)
        // A canvas edit: a new state, and a transition to it.
        (withNode, newId) = before.addNode(attributes = Attributes.of(Label -> "Saving"))
        source0 =
          before.nodeIds.find(n => !before.groupIds.exists(_.value == n.value) && n.value.endsWith("_start")).getOrElse(NodeId("Idle"))
        (edited, _) = withNode.addArrow(source0, newId)
        printed     = MermaidBackend().graphToText(edited, omitInternal = true)
        _ <- drawnIds(printed, edited)
      yield ()

  test("the graph keeps the kinds, labels, descriptions, groups, classes and styles"):
    val source = Samples.collectFirst { case ("everything the model keeps", s) => s }.get
    parse(source).map: graph =>
      def nodeAttr(id:    String, attr: AttributeId) = graph.nodes.get(NodeId(id)).flatMap(_.attributes.values.get(attr)).map(_.toString)
      def groupAttr(id:   String, attr: AttributeId) = graph.groups.get(GroupId(id)).flatMap(_.attributes.values.get(attr)).map(_.toString)
      def graphAttr(attr: AttributeId)               = graph.elements.graphAttributes.values.get(attr).map(_.toString)
      def expect(found: Option[String], expected: String, what: String) =
        check(found.contains(expected), s"$what: expected $expected, found $found")
      expect(nodeAttr("Idle", Label.attrId), "Waiting : for input", "the label of Idle")
      expect(nodeAttr("Idle", MermaidStateDescriptionsAttr), "first line\nsecond line", "the descriptions of Idle")
      expect(nodeAttr("F", MermaidStateTypeAttr), "fork", "the kind of F")
      expect(nodeAttr("J", MermaidStateTypeAttr), "join", "the kind of J")
      expect(nodeAttr("C", MermaidStateTypeAttr), "choice", "the kind of C")
      expect(nodeAttr("Pay", MermaidClassAttr), "bad", "the class of Pay")
      expect(nodeAttr("Y", MermaidClassAttr), "bad", "the class of Y")
      expect(nodeAttr("Ship", Style.attrId), "fill:#0f0", "the style of Ship")
      expect(groupAttr("Outer", Label.attrId), "Outer box", "the label of Outer")
      expect(graphAttr(Rankdir.attrId), "LR", "the direction")
      expect(graphAttr(Label.attrId), "Checkout", "the title")
      expect(graphAttr(AttributeId(MermaidClassDefPrefix + "bad")), "fill:#f00,color:white", "the classDef")
      check(graph.memberships.get(GroupId("Inner")).exists(_.equals(GroupId("Outer"))), s"Inner is not in Outer: ${graph.memberships}")
      check(graph.memberships.get(NodeId("X")).exists(_.equals(GroupId("Inner"))), s"X is not in Inner: ${graph.memberships}")
      check(graph.memberships.get(NodeId("Y")).exists(_.equals(GroupId("Outer"))), s"Y is not in Outer: ${graph.memberships}")
      val idleToFork = graph.arrows.values.exists: a =>
        a.source.value == "Idle" && a.attributes.values.get(Label.attrId).map(_.toString).contains("go ; now")
      check(idleToFork, "no transition Idle -> F : go ; now")
      check(graph.arrowIds.exists(_.value == "Pay->Ship/2"), s"no second transition Pay -> Ship: ${graph.arrowIds}")

  test("a collapsed composite state is drawn as one state, with the ids of the visible graph"):
    // The canvas renders the visible graph when a group is collapsed.
    val source = Samples.collectFirst { case ("composite", s) => s }.get
    for
      graph <- parse(source)
      visible = graph.toVisibleGraph(collapsedGroups = Set(GroupId("Active")))
      printed = MermaidBackend().graphToText(visible, omitInternal = false)
      _       = check(printed.startsWith(StateDiagramHeader), s"the visible graph is not a state diagram:\n$printed")
      _ <- drawnIds(printed, visible)
    yield ()
