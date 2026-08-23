package org.jpablo.graphexplorer.gxcore.command

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.{ArrowId, GroupId, NodeId}

/** The record tier: stored metadata, never the diagram text (D7.2).
  *
  * The property behind every one of these is §5.3.1's rule — anything here
  * survives a pull, so a regenerating origin never conflicts with a folded
  * cluster or a hidden node. A record command that touched `text` would break
  * the follow-the-file flow, which is why the first test asserts it does not.
  */
class RecordCommandsSpec extends FunSuite:

  private def diagram = Diagram(
    id = DiagramId("d1"),
    name = "Architecture",
    folder = FolderPath.parse("/systems"),
    format = "DOT",
    text = "digraph G { a -> b }",
    binding = None,
    metadata = DiagramMetadata.empty,
    createdAt = 1,
    updatedAt = 1
  )

  private def run(command: RecordCommand, d: Diagram = diagram) = RecordCommands.run(d, command)

  private def updated(command: RecordCommand, d: Diagram = diagram): Diagram =
    run(command, d).fold(e => fail(e.message), _.updatedDiagram.getOrElse(fail("expected a diagram")))

  test("no record command touches the diagram text"):
    // §5.3.1's design rule, asserted rather than assumed. If a command ever
    // needs to change the text it belongs in the DOCUMENT tier, and putting it
    // here would make it conflict with every pull.
    val commands = Vector(
      RecordCommand.Hide(Set(NodeId("a"))),
      RecordCommand.Collapse(Set(GroupId("g"))),
      RecordCommand.Tag(List("infra")),
      RecordCommand.SetNotes("a note"),
      RecordCommand.MoveToFolder("/other"),
      RecordCommand.Rename("New Name")
    )
    for command <- commands do
      assertEquals(updated(command).text, diagram.text, command.name)

  // ------------------------------------------------------------ view state

  test("hide stores the ElementRef spelling, so a node and a group cannot collide"):
    // The reason this matters: `hiddenElements` is a Set[String], and a bare
    // "n1" cannot say which kind of element was meant. Hiding node n1 would
    // then also hide group n1.
    val hidden = updated(RecordCommand.Hide(Set(NodeId("n1"), GroupId("n1"))))
    assertEquals(hidden.metadata.hiddenElements, Set("node:n1", "group:n1"))

  test("unhide removes only what it names"):
    val d = updated(RecordCommand.Hide(Set(NodeId("a"), NodeId("b"), ArrowId("e1"))))
    val after = updated(RecordCommand.Unhide(Set(NodeId("a"))), d)
    assertEquals(after.metadata.hiddenElements, Set("node:b", "arrow:e1"))

  test("unhiding a node does not unhide a group with the same id"):
    val d = updated(RecordCommand.Hide(Set(NodeId("x"), GroupId("x"))))
    val after = updated(RecordCommand.Unhide(Set(NodeId("x"))), d)
    assertEquals(after.metadata.hiddenElements, Set("group:x"))

  test("unhide-all clears the set"):
    val d = updated(RecordCommand.Hide(Set(NodeId("a"), NodeId("b"))))
    assertEquals(updated(RecordCommand.UnhideAll, d).metadata.hiddenElements, Set.empty[String])

  test("collapse and expand are symmetric"):
    val collapsed = updated(RecordCommand.Collapse(Set(GroupId("g1"), GroupId("g2"))))
    assertEquals(collapsed.metadata.collapsedGroups, Set("group:g1", "group:g2"))
    val expanded = updated(RecordCommand.Expand(Set(GroupId("g1"))), collapsed)
    assertEquals(expanded.metadata.collapsedGroups, Set("group:g2"))
    assertEquals(updated(RecordCommand.ExpandAll, expanded).metadata.collapsedGroups, Set.empty[String])

  test("hiding the same element twice is idempotent"):
    val once  = updated(RecordCommand.Hide(Set(NodeId("a"))))
    val twice = updated(RecordCommand.Hide(Set(NodeId("a"))), once)
    assertEquals(twice.metadata.hiddenElements, once.metadata.hiddenElements)

  // --------------------------------------------------------- organisation

  test("tags keep the order the user chose, and do not duplicate"):
    // A List rather than a Set because the order is the user's; a Set would
    // silently reorder it on every write.
    val tagged = updated(RecordCommand.Tag(List("infra", "draft")))
    assertEquals(tagged.metadata.tags, List("infra", "draft"))
    val again = updated(RecordCommand.Tag(List("draft", "public")), tagged)
    assertEquals(again.metadata.tags, List("infra", "draft", "public"))

  test("untag removes only the named tags"):
    val d = updated(RecordCommand.Tag(List("a", "b", "c")))
    assertEquals(updated(RecordCommand.Untag(List("b")), d).metadata.tags, List("a", "c"))

  test("move-to-folder parses the path the way the library spells folders"):
    val moved = updated(RecordCommand.MoveToFolder("//a//b/"))
    // FolderPath collapses empty segments, so two spellings are one folder.
    assertEquals(moved.folder, FolderPath.parse("/a/b"))
    assertEquals(moved.folder.render, "/a/b")

  test("rename changes the name"):
    assertEquals(updated(RecordCommand.Rename("Other")).name, "Other")

  test("a blank name is refused, because the library has been bitten by one"):
    run(RecordCommand.Rename("   ")) match
      case Left(CommandError.BadArgument(command, detail)) =>
        assertEquals(command, "rename-diagram")
        assert(detail.contains("blank"), detail)
      case other => fail(s"expected a BadArgument, got $other")

  // --------------------------------------------------------------- query

  test("get-record answers with the record, and changes nothing"):
    val d = updated(RecordCommand.Tag(List("infra")))
    val answer = run(RecordCommand.GetRecord, d).fold(e => fail(e.message), _.answer.getOrElse(fail("no answer")))
    assertEquals(answer("id").str, "d1")
    assertEquals(answer("folder").str, "/systems")
    assertEquals(answer("tags").arr.map(_.str).toVector, Vector("infra"))
    assert(run(RecordCommand.GetRecord, d).exists(_.updatedDiagram.isEmpty))

  // ---------------------------------------------------------- the wire

  test("every record command round-trips through the wire form"):
    val commands = Vector(
      RecordCommand.Hide(Set(NodeId("a"), GroupId("g"))),
      RecordCommand.Unhide(Set(ArrowId("e1"))),
      RecordCommand.UnhideAll,
      RecordCommand.Collapse(Set(GroupId("g1"))),
      RecordCommand.Expand(Set(GroupId("g1"))),
      RecordCommand.ExpandAll,
      RecordCommand.Tag(List("a", "b")),
      RecordCommand.Untag(List("a")),
      RecordCommand.SetNotes("note"),
      RecordCommand.MoveToFolder("/a/b"),
      RecordCommand.Rename("Name"),
      RecordCommand.GetRecord
    )
    for command <- commands do
      assertEquals(RecordCodec.decode(RecordCodec.encode(command)), Right(command), command.name)

  test("collapse refuses a node, since collapsing one cannot mean anything"):
    val frame = ujson.Obj(
      "command" -> "collapse",
      "params"  -> ujson.Obj("groups" -> ujson.Arr("group:g1", "node:a"))
    )
    RecordCodec.decode(frame) match
      case Left(CommandError.BadArgument(_, detail)) => assert(detail.contains("node:a"), detail)
      case other                                     => fail(s"expected a BadArgument, got $other")

  // --------------------------------------------------- one vocabulary

  test("the two tiers' names do not overlap"):
    // A name meaning one thing in one tier and something else in another is
    // exactly the ambiguity a vocabulary exists to prevent — and `AnyCommand`
    // dispatches on the name alone, so an overlap would silently pick one.
    val shared = DocumentCommand.names.toSet intersect RecordCommand.names.toSet
    assertEquals(shared, Set.empty[String], s"these names are claimed by both tiers: $shared")

  test("AnyCommand routes a name to the tier that owns it"):
    val hide = AnyCommand.decode(
      ujson.Obj("command" -> "hide", "params" -> ujson.Obj("targets" -> ujson.Arr("node:a")))
    )
    assertEquals(hide.map(_.tier), Right(Tier.Record))

    val list = AnyCommand.decode(ujson.Obj("command" -> "list-nodes", "params" -> ujson.Obj()))
    assertEquals(list.map(_.tier), Right(Tier.Document))

    // And both tiers are headless, which is the point of D7.2's split.
    assert(hide.exists(_.tier.headless))
    assert(list.exists(_.tier.headless))

  test("the session tier's names are distinct from both headless tiers"):
    // Three tiers, one vocabulary: a name may belong to exactly one of them, or
    // `gx run` and `gx session` would disagree about what the user meant.
    val headless = DocumentCommand.names.toSet ++ RecordCommand.names.toSet
    val shared   = headless intersect SessionCommand.names.toSet
    assertEquals(shared, Set.empty[String], s"claimed by more than one tier: $shared")

  test("the session tier is the only one that is not headless"):
    assertEquals(SessionCommand.tier, Tier.Session)
    assert(!SessionCommand.tier.headless)
    assert(DocumentCommand.tier.headless && RecordCommand.tier.headless)

  test("every session command round-trips through the wire form"):
    val commands = Vector(
      SessionCommand.Select(Set(NodeId("a"), GroupId("g"))),
      SessionCommand.AddToSelection(Set(ArrowId("e1"))),
      SessionCommand.ClearSelection,
      SessionCommand.ResetView,
      SessionCommand.PushText("doc-abc123", "digraph G { a }"),
      SessionCommand.WhatIsSelected
    )
    for command <- commands do
      assertEquals(SessionCodec.decode(SessionCodec.encode(command)), Right(command), command.name)

  test("a session command shares the frame shape of every other tier"):
    // The page receives `{command, params}` exactly as the socket does — one
    // envelope for the whole system, so there is no second format to learn.
    val frame = SessionCodec.encode(SessionCommand.Select(Set(NodeId("a"))))
    assertEquals(frame("command").str, "select")
    assertEquals(frame("params")("targets").arr.map(_.str).toVector, Vector("node:a"))

  test("a push names the document it is aimed at, and is refused without one"):
    // The whole point of the field. A push used to carry text alone, and the
    // page put it into whichever viewer was on screen — a write with no
    // addressee. A decoder that accepted a missing session would let that back.
    assertEquals(
      SessionCodec.decode(ujson.Obj("command" -> "push-text", "params" -> ujson.Obj("text" -> "x"))),
      Left(CommandError.BadArgument("push-text", "missing required 'sessionId'"))
    )
    assertEquals(
      SessionCodec.decode(
        ujson.Obj("command" -> "push-text", "params" -> ujson.Obj("sessionId" -> "", "text" -> "x"))
      ),
      Left(CommandError.BadArgument("push-text", "missing required 'sessionId'")),
      "a blank session names nothing either"
    )
    assertEquals(
      SessionCodec.decode(
        ujson.Obj("command" -> "push-text", "params" -> ujson.Obj("sessionId" -> "doc-a"))
      ),
      Left(CommandError.BadArgument("push-text", "missing required 'text'"))
    )

  test("an unknown name is refused by the combined vocabulary too"):
    assertEquals(
      AnyCommand.decode(ujson.Obj("command" -> "frobnicate", "params" -> ujson.Obj())),
      Left(CommandError.UnknownCommand("frobnicate"))
    )
