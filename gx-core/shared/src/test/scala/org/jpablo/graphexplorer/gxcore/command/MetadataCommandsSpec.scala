package org.jpablo.graphexplorer.gxcore.command

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.{ArrowId, DiagramId, GroupId, NodeId}

/** The metadata tier: stored metadata, never the source of the diagram (D7.2).
  *
  * The property behind every one of these is §5.3.1's rule — anything here
  * survives a pull, so a regenerating origin never conflicts with a folded
  * cluster or a hidden node. A metadata command that changes `text` breaks the
  * flow that follows the file. Thus the first test asserts that none does.
  */
class MetadataCommandsSpec extends FunSuite:

  private def diagram = Diagram(
    id = DiagramId("d1"),
    format = "DOT",
    text = "digraph G { a -> b }",
    binding = None,
    metadata = DiagramMetadata(name = "Architecture", folder = FolderPath.parse("/systems")),
    createdAt = 1,
    updatedAt = 1
  )

  private def run(command: MetadataCommand, d: Diagram = diagram) = MetadataCommands.run(d, command)

  private def updated(command: MetadataCommand, d: Diagram = diagram): Diagram =
    run(command, d).fold(e => fail(e.message), _.updatedDiagram.getOrElse(fail("expected a diagram")))

  test("no metadata command touches the diagram text"):
    // §5.3.1's design rule, asserted rather than assumed. A command that must
    // change the text belongs in the SOURCE tier. Here, it conflicts with every
    // pull.
    val commands = Vector(
      MetadataCommand.Hide(Set(NodeId("a"))),
      MetadataCommand.Collapse(Set(GroupId("g"))),
      MetadataCommand.Tag(List("infra")),
      MetadataCommand.SetNotes("a note"),
      MetadataCommand.MoveToFolder("/other"),
      MetadataCommand.Rename("New Name")
    )
    for command <- commands do
      assertEquals(updated(command).text, diagram.text, command.name)

  // ------------------------------------------------------------ view state

  test("hide stores the ElementRef spelling, so a node and a group cannot collide"):
    // The reason this matters: `hiddenElements` is a Set[String], and a bare
    // "n1" cannot say which kind of element was meant. Hiding node n1 would
    // then also hide group n1.
    val hidden = updated(MetadataCommand.Hide(Set(NodeId("n1"), GroupId("n1"))))
    assertEquals(hidden.metadata.hiddenElements, Set("node:n1", "group:n1"))

  test("unhide removes only what it names"):
    val d = updated(MetadataCommand.Hide(Set(NodeId("a"), NodeId("b"), ArrowId("e1"))))
    val after = updated(MetadataCommand.Unhide(Set(NodeId("a"))), d)
    assertEquals(after.metadata.hiddenElements, Set("node:b", "arrow:e1"))

  test("unhiding a node does not unhide a group with the same id"):
    val d = updated(MetadataCommand.Hide(Set(NodeId("x"), GroupId("x"))))
    val after = updated(MetadataCommand.Unhide(Set(NodeId("x"))), d)
    assertEquals(after.metadata.hiddenElements, Set("group:x"))

  test("unhide-all clears the set"):
    val d = updated(MetadataCommand.Hide(Set(NodeId("a"), NodeId("b"))))
    assertEquals(updated(MetadataCommand.UnhideAll, d).metadata.hiddenElements, Set.empty[String])

  test("collapse and expand are symmetric"):
    val collapsed = updated(MetadataCommand.Collapse(Set(GroupId("g1"), GroupId("g2"))))
    assertEquals(collapsed.metadata.collapsedGroups, Set("group:g1", "group:g2"))
    val expanded = updated(MetadataCommand.Expand(Set(GroupId("g1"))), collapsed)
    assertEquals(expanded.metadata.collapsedGroups, Set("group:g2"))
    assertEquals(updated(MetadataCommand.ExpandAll, expanded).metadata.collapsedGroups, Set.empty[String])

  test("hiding the same element twice is idempotent"):
    val once  = updated(MetadataCommand.Hide(Set(NodeId("a"))))
    val twice = updated(MetadataCommand.Hide(Set(NodeId("a"))), once)
    assertEquals(twice.metadata.hiddenElements, once.metadata.hiddenElements)

  // --------------------------------------------------------- organisation

  test("tags keep the order the user chose, and do not duplicate"):
    // A List rather than a Set because the order is the user's; a Set would
    // silently reorder it on every write.
    val tagged = updated(MetadataCommand.Tag(List("infra", "draft")))
    assertEquals(tagged.metadata.tags, List("infra", "draft"))
    val again = updated(MetadataCommand.Tag(List("draft", "public")), tagged)
    assertEquals(again.metadata.tags, List("infra", "draft", "public"))

  test("untag removes only the named tags"):
    val d = updated(MetadataCommand.Tag(List("a", "b", "c")))
    assertEquals(updated(MetadataCommand.Untag(List("b")), d).metadata.tags, List("a", "c"))

  test("move-to-folder parses the path the way the library spells folders"):
    val moved = updated(MetadataCommand.MoveToFolder("//a//b/"))
    // FolderPath collapses empty segments, so two spellings are one folder.
    assertEquals(moved.metadata.folder, FolderPath.parse("/a/b"))
    assertEquals(moved.metadata.folder.render, "/a/b")

  test("rename changes the name"):
    assertEquals(updated(MetadataCommand.Rename("Other")).metadata.name, "Other")

  test("rename-diagram and move-to-folder change only the name and the folder in the metadata"):
    val before = updated(MetadataCommand.Tag(List("infra")), updated(MetadataCommand.Hide(Set(NodeId("a")))))
    val renamed = updated(MetadataCommand.Rename("Other"), before)
    assertEquals(renamed.metadata, before.metadata.copy(name = "Other"))
    assertEquals(renamed.copy(metadata = before.metadata), before)
    val moved = updated(MetadataCommand.MoveToFolder("/a/b"), before)
    assertEquals(moved.metadata, before.metadata.copy(folder = FolderPath.parse("/a/b")))
    assertEquals(moved.copy(metadata = before.metadata), before)

  test("a blank name is refused, because the library has been bitten by one"):
    run(MetadataCommand.Rename("   ")) match
      case Left(CommandError.BadArgument(command, detail)) =>
        assertEquals(command, "rename-diagram")
        assert(detail.contains("blank"), detail)
      case other => fail(s"expected a BadArgument, got $other")

  // --------------------------------------------------------------- query

  private def answer(d: Diagram): ujson.Value =
    run(MetadataCommand.GetMetadata, d).fold(e => fail(e.message), _.answer.getOrElse(fail("no answer")))

  test("get-metadata answers with the metadata, and changes nothing"):
    val d = updated(MetadataCommand.Tag(List("infra")))
    assertEquals(answer(d)("name").str, "Architecture")
    assertEquals(answer(d)("folder").str, "/systems")
    assertEquals(answer(d)("tags").arr.map(_.str).toVector, Vector("infra"))
    assertEquals(answer(d)("notes").str, "")
    assertEquals(answer(d)("autoDetectFormat"), ujson.Null)
    assert(run(MetadataCommand.GetMetadata, d).exists(_.updatedDiagram.isEmpty))

  test("get-metadata answers with exactly the metadata keys, and nothing else"):
    // The id, the format, and the binding are not metadata. `gx ls --json`
    // prints the origin and the mode.
    val bound = diagram.copy(
      binding = Some(
        Binding(
          OriginUri.parse("file:///tmp/a.dot").fold(e => fail(e), identity),
          SyncMode.Pull,
          ContentHash.fromHex("ab"),
          1
        )
      )
    )
    assertEquals(
      answer(bound).obj.keySet.toSet,
      Set("name", "folder", "tags", "notes", "hidden", "collapsed", "autoDetectFormat")
    )

  test("get-metadata sorts the hidden and the collapsed refs, and gives autoDetectFormat as a boolean"):
    val hidden    = updated(MetadataCommand.Hide(Set(NodeId("b"), NodeId("a"), GroupId("c"))))
    val collapsed = updated(MetadataCommand.Collapse(Set(GroupId("z"), GroupId("y"))), hidden)
    val d         = collapsed.copy(metadata = collapsed.metadata.copy(autoDetectFormat = Some(false)))
    assertEquals(answer(d)("hidden").arr.map(_.str).toVector, Vector("group:c", "node:a", "node:b"))
    assertEquals(answer(d)("collapsed").arr.map(_.str).toVector, Vector("group:y", "group:z"))
    assertEquals(answer(d)("autoDetectFormat"), ujson.False)

  // ---------------------------------------------------------- the wire

  test("every metadata command round-trips through the wire form"):
    val commands = Vector(
      MetadataCommand.Hide(Set(NodeId("a"), GroupId("g"))),
      MetadataCommand.Unhide(Set(ArrowId("e1"))),
      MetadataCommand.UnhideAll,
      MetadataCommand.Collapse(Set(GroupId("g1"))),
      MetadataCommand.Expand(Set(GroupId("g1"))),
      MetadataCommand.ExpandAll,
      MetadataCommand.Tag(List("a", "b")),
      MetadataCommand.Untag(List("a")),
      MetadataCommand.SetNotes("note"),
      MetadataCommand.MoveToFolder("/a/b"),
      MetadataCommand.Rename("Name"),
      MetadataCommand.GetMetadata
    )
    for command <- commands do
      assertEquals(MetadataCodec.decode(MetadataCodec.encode(command)), Right(command), command.name)

  test("collapse refuses a node, since collapsing one cannot mean anything"):
    val frame = ujson.Obj(
      "command" -> "collapse",
      "params"  -> ujson.Obj("groups" -> ujson.Arr("group:g1", "node:a"))
    )
    MetadataCodec.decode(frame) match
      case Left(CommandError.BadArgument(_, detail)) => assert(detail.contains("node:a"), detail)
      case other                                     => fail(s"expected a BadArgument, got $other")

  // --------------------------------------------------- one vocabulary

  test("the two tiers' names do not overlap"):
    // A name meaning one thing in one tier and something else in another is
    // exactly the ambiguity a vocabulary exists to prevent — and `DiagramCommand`
    // dispatches on the name alone, so an overlap would silently pick one.
    val shared = SourceCommand.names.toSet intersect MetadataCommand.names.toSet
    assertEquals(shared, Set.empty[String], s"these names are claimed by both tiers: $shared")

  test("DiagramCommand routes a name to the tier that owns it"):
    val hide = DiagramCommand.decode(
      ujson.Obj("command" -> "hide", "params" -> ujson.Obj("targets" -> ujson.Arr("node:a")))
    )
    assertEquals(hide.map(_.tier), Right(Tier.Metadata))

    val list = DiagramCommand.decode(ujson.Obj("command" -> "list-nodes", "params" -> ujson.Obj()))
    assertEquals(list.map(_.tier), Right(Tier.Source))

    // And both tiers are headless, which is the point of D7.2's split.
    assert(hide.exists(_.tier.headless))
    assert(list.exists(_.tier.headless))

  test("the live view tier's names are distinct from both headless tiers"):
    // Three tiers, one vocabulary: a name can belong to only one of them.
    // Otherwise `gx run` and `gx view` disagree about what the user meant.
    val headless = SourceCommand.names.toSet ++ MetadataCommand.names.toSet
    val shared   = headless intersect LiveViewCommand.names.toSet
    assertEquals(shared, Set.empty[String], s"claimed by more than one tier: $shared")

  test("the live view tier is the only one that is not headless"):
    assertEquals(LiveViewCommand.tier, Tier.LiveView)
    assert(!LiveViewCommand.tier.headless)
    assert(SourceCommand.tier.headless && MetadataCommand.tier.headless)

  test("every live view command round-trips through the wire form"):
    val commands = Vector(
      LiveViewCommand.Select(Set(NodeId("a"), GroupId("g"))),
      LiveViewCommand.AddToSelection(Set(ArrowId("e1"))),
      LiveViewCommand.ClearSelection,
      LiveViewCommand.ResetView,
      LiveViewCommand.PushText("doc-abc123", "digraph G { a }"),
      LiveViewCommand.WhatIsSelected
    )
    for command <- commands do
      assertEquals(LiveViewCodec.decode(LiveViewCodec.encode(command)), Right(command), command.name)

  test("a live view command shares the frame shape of every other tier"):
    // The page receives `{command, params}` exactly as the socket does — one
    // envelope for the whole system, so there is no second format to learn.
    val frame = LiveViewCodec.encode(LiveViewCommand.Select(Set(NodeId("a"))))
    assertEquals(frame("command").str, "select")
    assertEquals(frame("params")("targets").arr.map(_.str).toVector, Vector("node:a"))

  test("a push names the document it is aimed at, and is refused without one"):
    // The whole point of the field. A push used to carry text alone, and the
    // page put it into whichever viewer was on screen — a write with no
    // addressee. A decoder that accepted a missing session would let that back.
    assertEquals(
      LiveViewCodec.decode(ujson.Obj("command" -> "push-text", "params" -> ujson.Obj("text" -> "x"))),
      Left(CommandError.BadArgument("push-text", "missing required 'sessionId'"))
    )
    assertEquals(
      LiveViewCodec.decode(
        ujson.Obj("command" -> "push-text", "params" -> ujson.Obj("sessionId" -> "", "text" -> "x"))
      ),
      Left(CommandError.BadArgument("push-text", "missing required 'sessionId'")),
      "a blank session names nothing either"
    )
    assertEquals(
      LiveViewCodec.decode(
        ujson.Obj("command" -> "push-text", "params" -> ujson.Obj("sessionId" -> "doc-a"))
      ),
      Left(CommandError.BadArgument("push-text", "missing required 'text'"))
    )

  test("an unknown name is refused by the combined vocabulary too"):
    assertEquals(
      DiagramCommand.decode(ujson.Obj("command" -> "frobnicate", "params" -> ujson.Obj())),
      Left(CommandError.UnknownCommand("frobnicate"))
    )
