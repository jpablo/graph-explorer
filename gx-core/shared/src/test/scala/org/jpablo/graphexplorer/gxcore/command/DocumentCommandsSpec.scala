package org.jpablo.graphexplorer.gxcore.command

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*

import scala.collection.immutable.VectorMap

/** The document tier, run against a real graph.
  *
  * The property worth protecting is D7.1's: a command must do *exactly* what
  * the corresponding `Ops` call does, because the UI's menu item and a socket
  * client's request are supposed to be the same operation. So each of these
  * compares the command's result against calling the op directly — a test that
  * fails if the two ever drift, which is the failure the vocabulary exists to
  * prevent.
  */
class DocumentCommandsSpec extends FunSuite:

  private def graph: ViewerGraph =
    ViewerGraph(
      elements = ViewerGraphElements(
        nodes = VectorMap(
          // Deliberately out of alphabetical order: a VectorMap keeps insertion
          // order, so a query answering in THIS order would be reporting how the
          // file was parsed rather than answering the question.
          NodeId("b") -> ViewerNode.nodeNoDefaults(NodeId("b"), Attributes.of("label" -> "Bee")),
          NodeId("a") -> ViewerNode.nodeNoDefaults(NodeId("a"), Attributes.of("label" -> "Ay")),
          NodeId("c") -> ViewerNode.nodeNoDefaults(NodeId("c"), Attributes.empty)
        ),
        arrows = Map(
          ArrowId("a->b") -> Arrow(NodeId("a"), NodeId("b")),
          ArrowId("b->c") -> Arrow(NodeId("b"), NodeId("c"))
        ),
        memberships = VectorMap.empty,
        groups = Map(GroupId("g1") -> ViewerGroup.group(GroupId("g1"), Attributes.of("label" -> "Group One"))),
        graphAttributes = Attributes.empty
      )
    )

  private def run(command: DocumentCommand, g: ViewerGraph = graph) =
    DocumentCommands.run(g, command)

  private def updated(command: DocumentCommand, g: ViewerGraph = graph): ViewerGraph =
    run(command, g).fold(e => fail(e.message), _.updatedGraph.getOrElse(fail("expected a graph")))

  private def answer(command: DocumentCommand, g: ViewerGraph = graph): ujson.Value =
    run(command, g).fold(e => fail(e.message), _.answer.getOrElse(fail("expected an answer")))

  // ------------------------------------------------ the vocabulary IS the ops

  test("set-attribute does exactly what updateAttributes does"):
    val targets = Set[ElementId](NodeId("a"), NodeId("c"))
    val viaCommand = updated(DocumentCommand.SetAttribute(targets, "color", "red"))
    val viaOps = graph.updateAttributes(
      ElementIds(targets),
      AttributeUpdates(
        Map(AttributeId("color") -> AttrStatus.Single(
          org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue("red")
        ))
      )
    )
    assertEquals(viaCommand.elements, viaOps.elements)
    assertEquals(attr(viaCommand, "a", "color"), Some("red"))

  test("remove-attribute does exactly what the op does"):
    val targets = Set[ElementId](NodeId("a"))
    val viaCommand = updated(DocumentCommand.RemoveAttribute(targets, "label"))
    val viaOps =
      graph.updateAttributes(ElementIds(targets), AttributeUpdates.remove(Set(AttributeId("label"))))
    assertEquals(viaCommand.elements, viaOps.elements)
    assertEquals(attr(viaCommand, "a", "label"), None)
    // And only the named target: a command must not touch what it did not name.
    assertEquals(attr(viaCommand, "b", "label"), Some("Bee"))

  test("group does exactly what moveToNewGroup does"):
    val targets = Set[ElementId](NodeId("a"), NodeId("b"))
    val viaCommand = updated(DocumentCommand.Group(targets, "cluster"))
    val viaOps     = graph.moveToNewGroup(ElementIds(targets), "cluster")
    assertEquals(viaCommand.elements, viaOps.elements)

    // What actually happens, which is worth writing down: a new group appears
    // holding both nodes, AND the pre-existing empty g1 disappears —
    // `moveToNewGroup` finishes with `removeEmptyGroups()`. The group COUNT is
    // therefore unchanged, so counting groups would have "passed" for a command
    // that did nothing at all.
    val newGroups = viaCommand.elements.groups.keySet -- graph.elements.groups.keySet
    assertEquals(newGroups.size, 1, "exactly one new group")
    val gid = newGroups.head
    assertEquals(
      viaCommand.elements.memberships.collect { case (m, g) if g == gid => m }.toSet,
      Set[GroupMemberId](NodeId("a"), NodeId("b"))
    )
    assert(!viaCommand.elements.groups.contains(GroupId("g1")), "the empty group should be gone")

  // ---------------------------------------------------------------- queries

  test("list-nodes answers with references and labels, in a stable order"):
    val result = answer(DocumentCommand.ListNodes).arr
    assertEquals(result.map(_("ref").str).toVector, Vector("node:a", "node:b", "node:c"))
    assertEquals(result.head("label").str, "Ay")

  test("a node with no label answers null rather than an empty string"):
    // "no label" and "an empty label" are the same thing in the model, since a
    // label is an AttrValue and an absent one is empty. A client asking what
    // the nodes are called should not have to know that.
    val result = answer(DocumentCommand.ListNodes).arr
    assertEquals(result.last("label"), ujson.Null)

  test("list-arrows answers with both endpoints"):
    val result = answer(DocumentCommand.ListArrows).arr
    assertEquals(result.map(_("ref").str).toVector, Vector("arrow:a->b", "arrow:b->c"))
    assertEquals(result.head("source").str, "node:a")
    assertEquals(result.head("target").str, "node:b")

  test("list-groups answers with references and labels"):
    val result = answer(DocumentCommand.ListGroups).arr
    assertEquals(result.map(_("ref").str).toVector, Vector("group:g1"))
    assertEquals(result.head("label").str, "Group One")

  test("get-attributes answers keyed by reference"):
    val result = answer(DocumentCommand.GetAttributes(Set(NodeId("a"), NodeId("b"))))
    assertEquals(result("node:a")("label").str, "Ay")
    assertEquals(result("node:b")("label").str, "Bee")

  test("a query returns no graph, and a mutation returns no answer"):
    // The distinction a caller depends on to know whether it must write
    // anything back.
    assert(run(DocumentCommand.ListNodes).exists(_.updatedGraph.isEmpty))
    assert(run(DocumentCommand.ResetAttributes(Set(NodeId("a")))).exists(_.answer.isEmpty))

  // ------------------------------------------------------------ refusals

  test("an inapplicable command is refused with a reason, not silently ignored"):
    // The UI greys out the menu item. A socket client has no menu, so the
    // applicability check has to live with the command rather than the button —
    // otherwise headless callers get a no-op that looks like success.
    run(DocumentCommand.SplitRecord(NodeId("a"))) match
      case Left(CommandError.NotApplicable(command, detail)) =>
        assertEquals(command, "split-record")
        assert(detail.contains("node:a"), detail)
      case other => fail(s"expected NotApplicable, got $other")

  test("transposing a node that is not a record is refused"):
    assert(run(DocumentCommand.TransposeRecord(NodeId("a"))).isLeft)

  test("a command naming an element that does not exist is refused"):
    // The reason this rule exists, found by writing the test that assumed the
    // opposite: `updateAttributes` treats an unknown id ASYMMETRICALLY —
    // arrows and groups are filtered out, but a node is CREATED. In the UI
    // that never shows, because a selection only ever contains elements that
    // exist. On a socket it shows immediately: one stale `node:foo` from an
    // agent would silently add a node to the user's diagram, and the reply
    // would say the command succeeded.
    run(DocumentCommand.SetAttribute(Set(NodeId("ghost")), "color", "red")) match
      case Left(CommandError.NotApplicable(command, detail)) =>
        assertEquals(command, "set-attribute")
        assert(detail.contains("node:ghost"), detail)
      case other => fail(s"expected NotApplicable, got $other")

  test("the refusal names every missing element, not just the first"):
    run(DocumentCommand.SetAttribute(Set(NodeId("ghost"), NodeId("a"), NodeId("wraith")), "color", "red")) match
      case Left(CommandError.NotApplicable(_, detail)) =>
        assert(detail.contains("node:ghost"), detail)
        assert(detail.contains("node:wraith"), detail)
        assert(!detail.contains("node:a"), s"should not blame the element that DOES exist: $detail")
      case other => fail(s"expected NotApplicable, got $other")

  test("a query naming a missing element is refused too"):
    // A query that silently answers about nothing is as misleading as a
    // mutation that silently does nothing.
    assert(run(DocumentCommand.GetAttributes(Set(ArrowId("nope")))).isLeft)

  test("the check covers all three kinds"):
    assert(run(DocumentCommand.RemoveAttribute(Set(ArrowId("nope")), "style")).isLeft)
    assert(run(DocumentCommand.Ungroup(Set(GroupId("nope")))).isLeft)
    assert(run(DocumentCommand.Ungroup(Set(GroupId("g1")))).isRight)

  // ------------------------------------------------------- wire to behaviour

  test("a command decoded from the wire runs the same as one built in Scala"):
    // The end-to-end property: what an agent sends and what the UI calls are
    // the same operation, which is the reason for having one vocabulary at all.
    val frame = ujson.Obj(
      "command" -> "set-attribute",
      "params" -> ujson.Obj(
        "targets" -> ujson.Arr("node:a"),
        "name"    -> "color",
        "value"   -> "blue"
      )
    )
    val decoded = CommandCodec.decode(frame).fold(e => fail(e.message), identity)
    assertEquals(
      updated(decoded).elements,
      updated(DocumentCommand.SetAttribute(Set(NodeId("a")), "color", "blue")).elements
    )

  private def attr(g: ViewerGraph, node: String, name: String): Option[String] =
    g.nodes.get(NodeId(node)).flatMap(_.attributes.get(AttributeId(name)).map(_.toString)).filter(_.nonEmpty)
