package org.jpablo.graphexplorer.gxcore.command

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.{ArrowId, GroupId, NodeId}

/** The metadata tier: the metadata of a diagram, never its source (D7.2).
  *
  * The property behind each of these is §5.3.1's rule: anything here survives
  * a write of the file by a tool, so a regenerated file never conflicts with a
  * folded cluster or a hidden node. The commands operate on `DiagramMetadata`
  * only, so they cannot change the source.
  */
class MetadataCommandsSpec extends FunSuite:

  private val meta = DiagramMetadata(name = "arch")

  private def updated(command: MetadataCommand, m: DiagramMetadata = meta): DiagramMetadata =
    MetadataCommands.run(m, command)

  // ------------------------------------------------------------ view state

  test("hide stores the ElementRef spelling, so a node and a group cannot collide"):
    // The reason this matters: `hiddenElements` is a Set[String], and a bare
    // "n1" cannot say which kind of element was meant. Hiding node n1 would
    // then also hide group n1.
    val hidden = updated(MetadataCommand.Hide(Set(NodeId("n1"), GroupId("n1"))))
    assertEquals(hidden.hiddenElements, Set("node:n1", "group:n1"))

  test("unhide removes only what it names"):
    val m     = updated(MetadataCommand.Hide(Set(NodeId("a"), NodeId("b"), ArrowId("e1"))))
    val after = updated(MetadataCommand.Unhide(Set(NodeId("a"))), m)
    assertEquals(after.hiddenElements, Set("node:b", "arrow:e1"))

  test("unhiding a node does not unhide a group with the same id"):
    val m     = updated(MetadataCommand.Hide(Set(NodeId("x"), GroupId("x"))))
    val after = updated(MetadataCommand.Unhide(Set(NodeId("x"))), m)
    assertEquals(after.hiddenElements, Set("group:x"))

  test("unhide-all clears the set"):
    val m = updated(MetadataCommand.Hide(Set(NodeId("a"), NodeId("b"))))
    assertEquals(updated(MetadataCommand.UnhideAll, m).hiddenElements, Set.empty[String])

  test("collapse and expand are symmetric"):
    val collapsed = updated(MetadataCommand.Collapse(Set(GroupId("g1"), GroupId("g2"))))
    assertEquals(collapsed.collapsedGroups, Set("group:g1", "group:g2"))
    val expanded = updated(MetadataCommand.Expand(Set(GroupId("g1"))), collapsed)
    assertEquals(expanded.collapsedGroups, Set("group:g2"))
    assertEquals(updated(MetadataCommand.ExpandAll, expanded).collapsedGroups, Set.empty[String])

  test("hiding the same element twice is idempotent"):
    val once  = updated(MetadataCommand.Hide(Set(NodeId("a"))))
    val twice = updated(MetadataCommand.Hide(Set(NodeId("a"))), once)
    assertEquals(twice, once)

  // ------------------------------------------------------- tags and notes

  test("tags keep the order the user chose, and do not duplicate"):
    // A List rather than a Set because the order is the user's; a Set would
    // silently reorder it on every write.
    val tagged = updated(MetadataCommand.Tag(List("infra", "draft", "infra")))
    assertEquals(tagged.tags, List("infra", "draft"))
    val again = updated(MetadataCommand.Tag(List("draft", "public")), tagged)
    assertEquals(again.tags, List("infra", "draft", "public"))

  test("untag removes only the named tags"):
    val m = updated(MetadataCommand.Tag(List("a", "b", "c")))
    assertEquals(updated(MetadataCommand.Untag(List("b")), m).tags, List("a", "c"))

  test("an empty note removes the note"):
    val noted = updated(MetadataCommand.SetNotes("a note"))
    assertEquals(noted.notes, "a note")
    assertEquals(updated(MetadataCommand.SetNotes(""), noted).notes, "")

  test("a command changes only its own field"):
    val before = updated(MetadataCommand.Tag(List("infra")), updated(MetadataCommand.Hide(Set(NodeId("a")))))
    assertEquals(updated(MetadataCommand.SetNotes("n"), before), before.copy(notes = "n"))
    assertEquals(updated(MetadataCommand.Collapse(Set(GroupId("g"))), before), before.copy(collapsedGroups = Set("group:g")))

  // ----------------------------------------------------------- live view

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
