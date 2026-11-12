package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph as dotToViewerGraph}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.read

/** The Mermaid writer (`viewerGraphToMermaidText`) must write text that the app reads back to the same graph.
  *
  * The writer runs when a person switches a diagram from DOT to Mermaid. It also runs when the app saves a Mermaid
  * diagram after an edit on the canvas. Each test reads a DOT diagram, as the switch does, and writes the graph as
  * Mermaid. Then the app's own reader (`MermaidFlowchartParser` and `toViewerGraph`) reads the text back.
  *
  * The writer can give a node a new id when Mermaid cannot use the old id. Thus most tests compare the names that the
  * canvas shows (the label, or the id when there is no label), not the ids. The app's parser is not Mermaid:
  * MermaidWriterRealMermaidSpec (viewer) does the same checks with the real Mermaid library.
  */
class MermaidWriterRoundTripSpec extends FunSuite:

  // ------------------------------------------------------------------ helpers

  private def fromDot(dot: String): ViewerGraph =
    ScalaGraphviz.structureJson(dot) match
      case Right(json) => dotToViewerGraph(read[SimpleGraph](json))
      case Left(err)   => fail(s"setup: the DOT reader rejects the fixture: $err\n$dot")

  /** A DOT string literal for `text`. A line break stays a real line break. */
  private def dotQuoted(text: String): String = "\"" + text.replace("\"", "\\\"") + "\""

  /** The text in Scala literal form, so a line break or a quote is visible in a failure message. */
  private def pprint(text: String): String =
    "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

  /** Write the graph as Mermaid, and read the text back with the app's parser. */
  private def roundTrip(g: ViewerGraph): (String, ViewerGraph) =
    val text = viewerGraphToMermaidText(g)
    MermaidFlowchartParser.parse(text) match
      case Right(mermaid) => (text, toViewerGraph(mermaid))
      case Left(err)      => fail(s"the app parser rejects the writer output: $err\n--- written ---\n$text")

  private def labelOf(attrs: Attributes): Option[String] =
    attrs.get(Label.attrId).map(_.toString).filter(_.nonEmpty)

  /** The name that the canvas shows for a node: its label, or its id when it has no label. */
  private def shownName(g: ViewerGraph, id: NodeId): String =
    g.nodes.get(id).flatMap(n => labelOf(n.attributes)).getOrElse(id.value)

  private def shownGroupName(g: ViewerGraph, id: GroupId): String =
    g.groups.get(id).flatMap(gr => labelOf(gr.attributes)).getOrElse(id.value)

  private def shownMemberName(g: ViewerGraph, member: GroupMemberId): String = member match
    case id: NodeId  => shownName(g, id)
    case id: GroupId => shownGroupName(g, id)

  /** The facts that a round trip must keep, in the names that the canvas shows. */
  final case class Shown(nodes: Vector[String], arrows: Vector[String], members: Vector[String]) derives CanEqual

  private def shown(g: ViewerGraph): Shown =
    val root = ViewerGraphElements.defaultRootId
    Shown(
      nodes = g.nodes.keys.map(shownName(g, _)).toVector.sorted,
      arrows = g.arrows.values.map(a => s"${shownName(g, a.source)} -> ${shownName(g, a.target)}").toVector.sorted,
      members = g.memberships.toVector
        .collect { case (member, parent) if parent != root => s"${shownMemberName(g, member)} in ${shownGroupName(g, parent)}" }
        .sorted
    )

  /** True when a 1:1 map from the node ids of `a` to the node ids of `b` changes the arrows of `a` into the arrows of
    * `b`.
    */
  private def sameUpToRenaming(a: ViewerGraph, b: ViewerGraph): Boolean =
    def arrows(g: ViewerGraph, rename: NodeId => NodeId): Vector[(String, String)] =
      g.arrows.values.toVector.map(x => (rename(x.source).value, rename(x.target).value)).sorted
    val aIds = a.nodes.keys.toVector
    val bIds = b.nodes.keys.toVector
    aIds.size == bIds.size && bIds.permutations.exists(perm => arrows(a, aIds.zip(perm).toMap) == arrows(b, identity))

  // ------------------------------------------------------------------ bug 1: a flowchart keyword as an id

  // Before the fix, the writer wrote a keyword id, for example `end`, with no change. The app's parser reads a line
  // `end` as the close of a subgraph. Real Mermaid rejects `end` and other keywords as a node id in each position (see
  // MermaidWriterRealMermaidSpec). The app's parser reads `start --> end` correctly, thus these tests use the
  // positions that break it. Now the writer gives such a node a new id, and it writes the old id as the label.

  test("bug 1: a node with the id `end` in a cluster keeps its cluster and its arrow"):
    val g          = fromDot("""digraph { subgraph cluster_flow { label="Flow"; start; end } start -> end }""")
    val (text, rt) = roundTrip(g)
    assertEquals(shown(rt), shown(g), s"--- written ---\n$text")

  test("bug 1: a node with the id `end` and no arrow stays in the graph"):
    val g          = fromDot("digraph { a -> b; end }")
    val (text, rt) = roundTrip(g)
    assertEquals(shown(rt), shown(g), s"--- written ---\n$text")

  test("bug 1: arrows from nodes with the ids `class` and `classDef` stay in the graph"):
    // The app's parser reads `class --> b` as a class assignment and `classDef --> c` as a class definition.
    val g          = fromDot("digraph { class -> b; classDef -> c }")
    val (text, rt) = roundTrip(g)
    assertEquals(shown(rt), shown(g), s"--- written ---\n$text")

  // ------------------------------------------------------------------ bug 2: the title has no YAML quotes

  // Before the fix, the writer wrote `title: <text>` with no YAML quotes. The app's parser is not a YAML parser: it
  // read `Plan: Q1` correctly, but it removed the quotes around `"Core"`, and it stopped at the line break. Real
  // Mermaid rejects or cuts more of these titles (see MermaidWriterRealMermaidSpec). Now the writer writes a
  // single-quoted YAML title when a plain title is not safe, and the app's reader removes the same quotes.
  test("bug 2: the title keeps a colon, quotes, a hash sign, and a line break"):
    // A one-line YAML title cannot keep a line break. The writer writes a space in its place.
    val titles = Vector(
      "Plan: Q1"      -> "Plan: Q1",
      "\"Draft\" plan" -> "\"Draft\" plan",
      "Release #2"    -> "Release #2",
      "\"Core\""      -> "\"Core\"",
      "it's"          -> "it's",
      "Plan\nQ1"      -> "Plan Q1"
    )
    val changed = titles.flatMap { (title, expected) =>
      val g = fromDot(s"digraph { label=${dotQuoted(title)}; a -> b }")
      assertEquals(labelOf(g.elements.graphAttributes), Some(title), "setup: the DOT reader changes the title")
      val (text, rt) = roundTrip(g)
      val obtained   = labelOf(rt.elements.graphAttributes)
      Option.when(!obtained.contains(expected))(s"title ${pprint(title)} came back as ${obtained.map(pprint)}\n--- written ---\n$text")
    }
    assert(changed.isEmpty, changed.mkString("\n"))

  // ------------------------------------------------------------------ bug 3: the cluster label has no escape

  // Before the fix, the writer wrote the cluster label inside `[...]` with no escape. The app's parser read most of
  // these labels correctly, but it removed the quotes around `"core"`. Real Mermaid rejects the others (see
  // MermaidWriterRealMermaidSpec). Now the writer escapes a cluster label as it escapes a node label.
  test("bug 3: a cluster label keeps brackets, quotes, parentheses, braces, and a pipe"):
    val labels = Vector("Stage [beta]", "the \"core\"", "\"core\"", "a (b)", "x {y}", "a | b")
    val changed = labels.flatMap { label =>
      val g = fromDot(s"digraph { subgraph cluster_s { label=${dotQuoted(label)}; a } a -> b }")
      assertEquals(shown(g).members, Vector(s"a in $label"), "setup: the DOT reader changes the cluster label")
      val (text, rt) = roundTrip(g)
      Option.when(shown(rt) != shown(g))(s"label ${pprint(label)} came back as ${shown(rt).members}\n--- written ---\n$text")
    }
    assert(changed.isEmpty, changed.mkString("\n"))

  // ------------------------------------------------------------------ bug 4: two ids become one id

  test("bug 4: the nodes `a b` and `a_b` stay two nodes"):
    // Before the fix, the writer replaced each unsafe character with `_`, and it did not look for a collision.
    val g          = fromDot("""digraph { "a b" -> c; a_b -> d }""")
    val (text, rt) = roundTrip(g)
    assertEquals(rt.nodes.size, g.nodes.size, s"the two ids merge into one node\n--- written ---\n$text")
    assert(sameUpToRenaming(g, rt), s"the arrows do not map 1:1\n--- written ---\n$text")

  // ------------------------------------------------------------------ other findings

  test("extra: a node with an unsafe id and an arrow keeps the name that the canvas shows"):
    // The writer gives `a b` a new id. Before the fix, it wrote the label `[a b]` only for a node on a line of its
    // own, and a node with an arrow and no label got no line of its own. Thus the canvas showed `a_b`, not `a b`.
    val g          = fromDot("""digraph { "a b" -> c }""")
    val (text, rt) = roundTrip(g)
    assertEquals(shown(rt), shown(g), s"--- written ---\n$text")

  test("extra (app parser): an arrow from a node with the id `Class` stays in the graph"):
    // Real Mermaid reads `Class --> b` as an arrow. Before the fix, the app's parser matched `class` with no regard
    // to case, and it read the line as a class assignment.
    val g          = fromDot("digraph { Class -> b; ClassDef -> c }")
    val (text, rt) = roundTrip(g)
    assertEquals(shown(rt), shown(g), s"--- written ---\n$text")

  test("extra: a node and a cluster with the same id stay apart"):
    // DOT keeps the ids of nodes and clusters apart, and the DOT reader takes `cluster_` off a cluster name. Thus the
    // node `g` and the cluster `cluster_g` both have the id `g`. Mermaid has one set of ids for nodes and subgraphs,
    // and before the fix the writer did not look for this collision.
    val g          = fromDot("""digraph { subgraph cluster_g { label="Group"; g } g -> b }""")
    val (text, rt) = roundTrip(g)
    assertEquals(shown(rt), shown(g), s"--- written ---\n$text")

  test("an arrow to a subgraph stays an arrow to the subgraph"):
    // Mermaid lets an arrow end at a subgraph. The Mermaid reader then makes a node with no attributes that has the id
    // of the subgraph. The writer must keep that id for the subgraph and for the arrow. If the subgraph gets a new id,
    // the arrow ends at a new node `sg`, not at the subgraph.
    val source =
      """flowchart LR
        |  subgraph sg [Group]
        |    a
        |  end
        |  b --> sg
        |""".stripMargin
    val g          = toViewerGraph(MermaidFlowchartParser.parse(source).fold(e => fail(s"setup: $e"), identity))
    val (text, rt) = roundTrip(g)
    assert(text.contains("  subgraph sg [Group]\n"), s"the subgraph has a new id\n--- written ---\n$text")
    assert(text.contains("  b --> sg\n"), s"the arrow does not end at the subgraph\n--- written ---\n$text")
    assertEquals(rt.groups.keySet, g.groups.keySet, s"--- written ---\n$text")
    assertEquals(rt.nodes.keySet, g.nodes.keySet, s"--- written ---\n$text")
    assertEquals(shown(rt), shown(g), s"--- written ---\n$text")
