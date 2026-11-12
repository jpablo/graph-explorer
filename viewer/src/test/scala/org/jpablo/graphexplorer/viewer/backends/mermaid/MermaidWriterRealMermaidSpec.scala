package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph as dotToViewerGraph}
import org.jpablo.graphexplorer.viewer.backends.mermaid.RealMermaid.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.read

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

/** Real Mermaid must read the text of the Mermaid writer (`viewerGraphToMermaidText`) back to the same graph.
  *
  * MermaidWriterRoundTripSpec (shared) reads the text with the app's own parser. That parser is not Mermaid, and it
  * accepts text that Mermaid rejects. This spec reads the text with the real Mermaid library, as the canvas does:
  * `MermaidBackend.textToGraph` for the graph, and `MermaidBackend.textToSvg` for the drawing.
  *
  * The writer can give a node a new id when Mermaid cannot use the old id. Thus the checks compare the names that the
  * canvas shows (the label, or the id when there is no label), not the ids.
  */
class MermaidWriterRealMermaidSpec extends FunSuite with LoggedChecks:

  def checkPrefix = "MermaidWriterRealMermaidSpec"

  // ------------------------------------------------------------------ helpers

  private def fromDot(dot: String): ViewerGraph =
    ScalaGraphviz.structureJson(dot) match
      case Right(json) => dotToViewerGraph(read[SimpleGraph](json))
      case Left(err)   => fail(s"setup: the DOT reader rejects the fixture: $err\n$dot")

  /** A DOT string literal for `text`. A line break stays a real line break. */
  private def dotQuoted(text: String): String = "\"" + text.replace("\"", "\\\"") + "\""

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

  /** Write the DOT graph as Mermaid, and read the text back with real Mermaid. Gives a clue when the graph changes. */
  private def roundTripClue(name: String, g: ViewerGraph): Future[Option[String]] =
    val text = viewerGraphToMermaidText(g)
    readWithMermaid(text).map:
      case Failure(e)  => Some(s"$name: real Mermaid rejects the text: ${firstLine(e)}\n--- written ---\n$text")
      case Success(rt) =>
        Option.when(shown(rt) != shown(g))(s"$name: the graph changed\n  obtained: ${shown(rt)}\n  expected: ${shown(g)}\n--- written ---\n$text")

  // ------------------------------------------------------------------ bug 1: a flowchart keyword as an id

  test("bug 1: real Mermaid reads the Mermaid form of DOT `start -> end`"):
    val g = fromDot("digraph { start -> end }")
    roundTripClue("`start -> end`", g).map(clue => check(clue.isEmpty, clue.getOrElse("")))

  // Each id below breaks real Mermaid 11.12 in an arrow. A check of each id one by one found this list. `click` breaks
  // only as the target of an arrow. Mermaid accepts these ids, thus they are not in the list: `End`, `END`, `Style`,
  // `Graph`, `Class`, `direction`, `default`, `accTitle`, `accDescr`, `o`, `x`, `TB`, `node`, `callback`, `end_x`,
  // `x-end`, `click-x`, `call-x`, `href-x`.
  private val KeywordNodeIds = Vector(
    "end", "subgraph", "graph", "flowchart", "flowchart-elk", "style", "class", "classDef", "linkStyle", "click",
    "call", "href", "interpolate", "_self", "_blank", "_parent", "_top",
    // A keyword, then `-` or a letter that is not ASCII: the lexer reads the keyword first.
    "end-x", "style-x", "graph-x", "end\u00e9",
    // Not a keyword: Mermaid reads `--` as the start of a link.
    "a--b"
  )

  test("bug 1: real Mermaid reads back a node whose id is a flowchart keyword"):
    oneByOne(KeywordNodeIds) { id =>
      roundTripClue(s"node id '$id'", fromDot(s"digraph { start -> ${dotQuoted(id)} -> stop }"))
    }.map { clues =>
      val broken = clues.flatten
      check(broken.isEmpty, s"real Mermaid does not read back ${broken.size} of ${KeywordNodeIds.size} node ids:\n${broken.mkString("\n")}")
    }

  // A cluster id comes after `subgraph`, and in a `style` line when the cluster has a fill color. The first eight ids
  // break after `subgraph`. The other ids break in the `style` line. With no fill color, Mermaid reads `subgraph click`
  // as a subgraph with no id, and the cluster loses its members.
  private val KeywordGroupIds = Vector(
    "default", "call", "href", "interpolate", "_self", "_blank", "_parent", "_top",
    "end", "subgraph", "style", "class", "classDef", "linkStyle", "click", "graph"
  )

  test("bug 1: real Mermaid reads back a cluster whose id is a flowchart keyword"):
    oneByOne(KeywordGroupIds) { id =>
      val g = fromDot(s"""digraph { subgraph ${dotQuoted("cluster_" + id)} { label="Group"; style=filled; fillcolor="#eeeeff"; a } a -> b }""")
      check(g.groups.keySet == Set(GroupId(id)), s"setup: the DOT reader gives the group ids ${g.groups.keySet}, not '$id'")
      roundTripClue(s"cluster id '$id'", g)
    }.map { clues =>
      val broken = clues.flatten
      check(broken.isEmpty, s"real Mermaid does not read back ${broken.size} of ${KeywordGroupIds.size} cluster ids:\n${broken.mkString("\n")}")
    }

  // ------------------------------------------------------------------ bug 2: the title has no YAML quotes

  // The drawing shows the title that real Mermaid reads from the YAML, thus this test reads the title from the
  // drawing (`text.flowchartTitleText`). `MermaidBackend.textToGraph` reads the title with the app's own reader.
  test("bug 2: real Mermaid draws the title with a colon, quotes, a hash sign, and a line break"):
    // A double-quoted YAML title keeps a line break as `\n` (`MermaidSourceScan.titleToYaml`).
    val titles = Vector(
      "Plan: Q1"      -> "Plan: Q1",
      "\"Draft\" plan" -> "\"Draft\" plan",
      "Release #2"    -> "Release #2",
      "\"Core\""      -> "\"Core\"",
      "it's"          -> "it's",
      "Plan\nQ1"      -> "Plan\nQ1"
    )
    oneByOne(titles) { (title, expected) =>
      val g = fromDot(s"digraph { label=${dotQuoted(title)}; a -> b }")
      check(labelOf(g.elements.graphAttributes).contains(title), "setup: the DOT reader changes the title")
      val text = viewerGraphToMermaidText(g)
      drawWithMermaid(text).map:
        case Failure(e) => Some(s"title ${literal(title)}: real Mermaid rejects the text: ${firstLine(e)}\n--- written ---\n$text")
        case Success(svg) =>
          val drawn = Option(svg.querySelector("text.flowchartTitleText")).map(_.textContent)
          Option.when(!drawn.contains(expected))(s"title ${literal(title)}: real Mermaid draws ${drawn.map(literal)}\n--- written ---\n$text")
    }.map { clues =>
      val broken = clues.flatten
      check(broken.isEmpty, s"real Mermaid does not draw ${broken.size} of ${titles.size} titles:\n${broken.mkString("\n")}")
    }

  // ------------------------------------------------------------------ bug 3: the cluster label has no escape

  // Real Mermaid changes `#quot;` in the source into an internal code, and `getSubGraphs` gives the title with that
  // code. Thus an escape in the writer alone does not give back `the "core"`: the parse path must also decode the title.
  test("bug 3: real Mermaid reads back a cluster label with brackets, quotes, parentheses, braces, and a pipe"):
    val labels = Vector("Stage [beta]", "the \"core\"", "\"core\"", "a (b)", "x {y}", "a | b")
    oneByOne(labels) { label =>
      val g = fromDot(s"digraph { subgraph cluster_s { label=${dotQuoted(label)}; a } a -> b }")
      check(shown(g).members == Vector(s"a in $label"), "setup: the DOT reader changes the cluster label")
      roundTripClue(s"cluster label ${literal(label)}", g)
    }.map { clues =>
      val broken = clues.flatten
      check(broken.isEmpty, s"real Mermaid does not read back ${broken.size} of ${labels.size} cluster labels:\n${broken.mkString("\n")}")
    }

  // ------------------------------------------------------------------ bug 4: a quote in a node label or an arrow label

  /** The label of each node and each arrow that has a label, with the ids of the node or of the two ends. */
  private def labels(g: ViewerGraph): Vector[String] =
    val nodes  = g.nodes.toVector.flatMap((id, n) => labelOf(n.attributes).map(l => s"${id.value}: $l"))
    val arrows = g.arrows.values.toVector.flatMap(a => labelOf(a.attributes).map(l => s"${a.source.value} -> ${a.target.value}: $l"))
    (nodes ++ arrows).sorted

  // Real Mermaid changes `#quot;` in the source into an internal code, and `getVertices` and `getEdges` give the label
  // with that code. Thus the parse path must decode node labels and arrow labels too, as it does for a cluster title.
  // A `[` makes the writer quote the label too. The last label has no quote: it must not change.
  test("bug 4: real Mermaid reads back a node label and an arrow label with quotes"):
    val texts = Vector("say \"hi\"", "\"core\"", "Stage [\"beta\"]", "Stage [beta]")
    oneByOne(texts) { label =>
      val g = fromDot(s"digraph { a [label=${dotQuoted(label)}]; a -> b [label=${dotQuoted(label)}] }")
      check(labels(g) == Vector(s"a -> b: $label", s"a: $label"), s"setup: the DOT reader changes the labels: ${labels(g)}")
      val text = viewerGraphToMermaidText(g)
      readWithMermaid(text).map:
        case Failure(e) => Some(s"label ${literal(label)}: real Mermaid rejects the text: ${firstLine(e)}\n--- written ---\n$text")
        case Success(rt) =>
          Option.when(labels(rt) != labels(g))(
            s"label ${literal(label)}: the labels changed\n  obtained: ${labels(rt).map(literal)}\n  expected: ${labels(g).map(literal)}\n--- written ---\n$text"
          )
    }.map { clues =>
      val broken = clues.flatten
      check(broken.isEmpty, s"real Mermaid does not read back ${broken.size} of ${texts.size} labels:\n${broken.mkString("\n")}")
    }
