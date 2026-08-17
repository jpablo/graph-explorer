package org.jpablo.graphexplorer.gxcore.command

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.models.{ArrowId, GroupId, NodeId}

/** The vocabulary as an interface: names, references, and the wire form.
  *
  * D7.1's finding is that the six `Ops` modules already are the vocabulary and
  * what they lack is *addressability*. These are the tests for the addressing —
  * the interpreter's behaviour is tested separately, against a real graph.
  */
class CommandVocabularySpec extends FunSuite:

  // ------------------------------------------------------------ references

  test("a reference renders and parses back to the same id"):
    val ids = Vector(NodeId("a"), ArrowId("a->b"), GroupId("cluster_1"))
    for id <- ids do
      assertEquals(ElementRef.parse(ElementRef.render(id)), Right(id))

  test("the wire spelling is the one the model already uses"):
    // Not a new scheme: this string is what `toSvg` has produced for years, so
    // a reference read off the DOM, out of a command, or out of an audit log is
    // the same text.
    assertEquals(ElementRef.render(NodeId("a")), "node:a")
    assertEquals(ElementRef.render(ArrowId("e1")), "arrow:e1")
    assertEquals(ElementRef.render(GroupId("g1")), "group:g1")

  test("an id containing a colon survives, because only the FIRST is a separator"):
    // Graphviz ports are spelled `node:port`, so this is not a hypothetical.
    val id = NodeId("record:port")
    assertEquals(ElementRef.parse(ElementRef.render(id)), Right(id))

  test("a bad reference says what is wrong and what was expected"):
    val cases = Vector("banana:x", "a", "node:")
    for text <- cases do
      ElementRef.parse(text) match
        case Left(message) =>
          assert(message.contains(text), s"the message should quote the input: $message")
        case Right(id) => fail(s"'$text' should not have parsed, got $id")

    // A wrong KIND names the kinds that exist, rather than only rejecting.
    assert(ElementRef.parse("banana:x").left.exists(_.contains("node")))

  test("parsing many reports every bad one, not just the first"):
    val result = ElementRef.parseAll(Vector("node:a", "banana:b", "node:c", "melon:d"))
    val message = result.left.getOrElse(fail("should have failed"))
    assert(message.contains("banana"), message)
    // The failure an agent issuing a batch actually cares about: it should not
    // have to fix one reference, resend, and learn about the next.
    assert(message.contains("melon"), message)

  // ----------------------------------------------------------------- names

  test("every command has a name, and every name is distinct"):
    val commands = Vector(
      DocumentCommand.SetAttribute(Set(NodeId("a")), "color", "red"),
      DocumentCommand.RemoveAttribute(Set(NodeId("a")), "color"),
      DocumentCommand.ResetAttributes(Set(NodeId("a"))),
      DocumentCommand.Group(Set(NodeId("a")), "g"),
      DocumentCommand.Ungroup(Set(GroupId("g"))),
      DocumentCommand.CombineIntoRecord(Set(NodeId("a"))),
      DocumentCommand.SplitRecord(NodeId("a")),
      DocumentCommand.TransposeRecord(NodeId("a")),
      DocumentCommand.ListNodes,
      DocumentCommand.ListArrows,
      DocumentCommand.ListGroups,
      DocumentCommand.GetAttributes(Set(NodeId("a")))
    )
    val names = commands.map(_.name)
    assertEquals(names.distinct.size, names.size, s"duplicate command names: $names")

    // `names` is what help text and the unknown-command message are built from,
    // so a case added without listing it would leave the CLI unable to mention
    // a command it can run.
    assertEquals(names.sorted, DocumentCommand.names.sorted)

  test("names are kebab-case, because they are typed and not written in Scala"):
    for name <- DocumentCommand.names do
      assert(
        name.forall(c => c.isLower || c == '-'),
        s"'$name' should be lower-kebab-case"
      )

  // ------------------------------------------------------------- the wire

  test("every command round-trips through the wire form"):
    val commands = Vector(
      DocumentCommand.SetAttribute(Set(NodeId("a"), GroupId("g")), "color", "red"),
      DocumentCommand.RemoveAttribute(Set(ArrowId("e1")), "style"),
      DocumentCommand.ResetAttributes(Set(NodeId("a"))),
      DocumentCommand.Group(Set(NodeId("a"), NodeId("b")), "cluster"),
      DocumentCommand.Group(Set(NodeId("a")), ""),
      DocumentCommand.Ungroup(Set(GroupId("g"))),
      DocumentCommand.CombineIntoRecord(Set(NodeId("a"), NodeId("b"))),
      DocumentCommand.SplitRecord(NodeId("r")),
      DocumentCommand.TransposeRecord(NodeId("r")),
      DocumentCommand.ListNodes,
      DocumentCommand.ListArrows,
      DocumentCommand.ListGroups,
      DocumentCommand.GetAttributes(Set(NodeId("a")))
    )
    for command <- commands do
      assertEquals(CommandCodec.decode(CommandCodec.encode(command)), Right(command), command.name)

  test("an encoded command is stable, so two identical commands encode identically"):
    // A Set has no order. Without sorting, the same command would produce
    // different bytes on different runs — which makes an audit log diff-noisy
    // and two identical recorded commands compare unequal on replay.
    val a = DocumentCommand.Group(Set(NodeId("c"), NodeId("a"), NodeId("b")), "g")
    val b = DocumentCommand.Group(Set(NodeId("b"), NodeId("c"), NodeId("a")), "g")
    assertEquals(CommandCodec.encode(a).render(), CommandCodec.encode(b).render())
    assertEquals(
      CommandCodec.encode(a)("params")("targets").arr.map(_.str).toVector,
      Vector("node:a", "node:b", "node:c")
    )

  test("the frame shape is D4's RPC, so a command IS a method call"):
    val frame = CommandCodec.encode(DocumentCommand.ListNodes)
    assertEquals(frame("command").str, "list-nodes")
    assert(frame.value.contains("params"), "a command always carries params, even when empty")

  // ------------------------------------------------------------- refusals

  test("an unknown command is named, not swallowed"):
    val frame = ujson.Obj("command" -> "frobnicate", "params" -> ujson.Obj())
    assertEquals(CommandCodec.decode(frame), Left(CommandError.UnknownCommand("frobnicate")))

  test("a missing argument says which one"):
    val frame = ujson.Obj("command" -> "set-attribute", "params" -> ujson.Obj("targets" -> ujson.Arr("node:a")))
    CommandCodec.decode(frame) match
      case Left(CommandError.BadArgument(command, detail)) =>
        assertEquals(command, "set-attribute")
        assert(detail.contains("name") || detail.contains("value"), detail)
      case other => fail(s"expected a BadArgument, got $other")

  test("the wrong KIND of reference is a category error, not a silent drop"):
    // `combine-into-record group:g1` asking for nodes must not quietly proceed
    // with the group removed — that would do something adjacent to what was
    // asked, which is worse than refusing.
    val frame = ujson.Obj(
      "command" -> "combine-into-record",
      "params"  -> ujson.Obj("nodes" -> ujson.Arr("node:a", "group:g1"))
    )
    CommandCodec.decode(frame) match
      case Left(CommandError.BadArgument(_, detail)) =>
        assert(detail.contains("group:g1"), detail)
      case other => fail(s"expected a BadArgument, got $other")

  test("a frame that is not an object, or has no name, is refused"):
    assert(CommandCodec.decode(ujson.Arr(1, 2)).isLeft)
    assert(CommandCodec.decode(ujson.Obj("params" -> ujson.Obj())).isLeft)

  // ----------------------------------------------------------------- tiers

  test("the document tier is headless, the session tier is not"):
    // D7.2's whole point: the split is about what an operation is ABOUT, and
    // that is what decides whether it needs a window.
    assert(Tier.Document.headless)
    assert(Tier.Record.headless)
    assert(!Tier.Session.headless)
    assertEquals(DocumentCommand.tier, Tier.Document)

  test("queries are distinguished from mutations"):
    assert(DocumentCommand.ListNodes.isQuery)
    assert(DocumentCommand.GetAttributes(Set(NodeId("a"))).isQuery)
    assert(!DocumentCommand.Ungroup(Set(GroupId("g"))).isQuery)
