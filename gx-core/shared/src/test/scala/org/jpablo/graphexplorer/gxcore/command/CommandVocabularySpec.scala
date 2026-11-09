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
    // A refusal names the command (`CommandError`), so two commands with one
    // name would give one message for two different refusals.
    val names = CommandVocabularySpec.EveryCommand.map(_.name)
    assertEquals(names.distinct.size, names.size, s"duplicate command names: $names")

  test("names are kebab-case, because they are typed and not written in Scala"):
    for name <- CommandVocabularySpec.EveryCommand.map(_.name) do
      assert(
        name.forall(c => c.isLower || c == '-'),
        s"'$name' should be lower-kebab-case"
      )

  // ----------------------------------------------------------------- tiers

  test("the source tier is headless, the live view tier is not"):
    // D7.2's whole point: the split is about what an operation is ABOUT, and
    // that is what decides whether it needs a window.
    assert(Tier.Source.headless)
    assert(Tier.Metadata.headless)
    assert(!Tier.LiveView.headless)
    assertEquals(SourceCommand.tier, Tier.Source)

  test("queries are distinguished from mutations"):
    assert(SourceCommand.ListNodes.isQuery)
    assert(SourceCommand.GetAttributes(Set(NodeId("a"))).isQuery)
    assert(!SourceCommand.Ungroup(Set(GroupId("g"))).isQuery)

object CommandVocabularySpec:
  /** One value of each source command. */
  val EveryCommand: Vector[SourceCommand] = Vector(
    SourceCommand.SetAttribute(Set(NodeId("a")), "color", "red"),
    SourceCommand.RemoveAttribute(Set(NodeId("a")), "color"),
    SourceCommand.ResetAttributes(Set(NodeId("a"))),
    SourceCommand.Group(Set(NodeId("a")), "g"),
    SourceCommand.Ungroup(Set(GroupId("g"))),
    SourceCommand.CombineIntoRecord(Set(NodeId("a"))),
    SourceCommand.SplitRecord(NodeId("a")),
    SourceCommand.TransposeRecord(NodeId("a")),
    SourceCommand.ListNodes,
    SourceCommand.ListArrows,
    SourceCommand.ListGroups,
    SourceCommand.GetAttributes(Set(NodeId("a")))
  )
