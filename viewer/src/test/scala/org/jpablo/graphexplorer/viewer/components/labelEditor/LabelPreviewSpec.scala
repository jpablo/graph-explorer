package org.jpablo.graphexplorer.viewer.components.labelEditor

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph as dotToViewerGraph}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.*
import upickle.default.read

/** The label dialog previews a label as the canvas draws it.
  *
  * `LabelPreview.previewDot` makes a small DOT graph for one element. The
  * graph has the label of the dialog, and the shape, the style, the colors
  * and the font of the element, also the ones that the element gets from the
  * `node [...]` and `edge [...]` defaults of the diagram. These tests render
  * that DOT with the Graphviz port, as the canvas does.
  *
  * The checks throw AssertionError, not munit's `assert`: through the Scala.js
  * test bridge and the sbt client, a munit FailException arrives with no message.
  */
class LabelPreviewSpec extends FunSuite:

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then throw AssertionError(message)

  private val diagram =
    """digraph G {
      |  node [shape=box, style="rounded,filled", margin="0.18,0.12"];
      |  edge [fontname="Menlo", fontsize=11];
      |  subgraph cluster_steps { label="Steps"; fontname="Georgia"; fontsize=18;
      |    split [fillcolor="#EBF6F3", color="#438173",
      |      label=<<FONT FACE="Menlo" POINT-SIZE="16"><B>Range.split[0, 3]</B></FONT>>];
      |    left;
      |  }
      |  split -> left [label="left: Range[0, 0]"];
      |}""".stripMargin

  private val graph: ViewerGraph =
    ScalaGraphviz.structureJson(diagram) match
      case Right(json) => dotToViewerGraph(read[SimpleGraph](json))
      case Left(err)   => throw AssertionError(s"setup: the fixture does not parse: $err")

  private def arrowId: ArrowId =
    graph.arrows.keys.headOption.getOrElse(throw AssertionError("setup: the fixture has no arrow"))

  private def groupId: GroupId =
    graph.groups.keys.headOption.getOrElse(throw AssertionError("setup: the fixture has no group"))

  /** The SVG of the preview DOT. */
  private def svg(element: ElementId, label: String): String =
    val dot = LabelPreview.previewDot(graph, element, label)
    val r   = ScalaGraphviz.renderFormats(dot, Seq("svg"))
    check(r.status == "success", s"the preview DOT does not render: ${r.errors}\n$dot")
    r.output("svg")

  /** The `<text>` elements of an SVG. */
  private def texts(svgText: String): List[String] =
    "<text[^>]*>[^<]*</text>".r.findAllIn(svgText).toList

  test("a node label previews with the shape, the style and the colors of the node"):
    val out = svg(NodeId("split"), """<FONT FACE="Menlo" POINT-SIZE="16"><B>NEW</B></FONT>""")
    check(out.contains("#ebf6f3") || out.contains("#EBF6F3"), s"the preview has no fill color of the node:\n$out")
    check(out.contains("#438173"), s"the preview has no border color of the node:\n$out")
    // A rounded box is a path; a plain box would be a polygon.
    check(out.contains("<path"), s"the preview shape is not a rounded box:\n$out")

  test("a node label previews the text of the dialog, not the old label"):
    val t = texts(svg(NodeId("split"), """<FONT FACE="Menlo" POINT-SIZE="16"><B>NEW</B></FONT>"""))
    check(t.exists(_.contains(">NEW<")), s"the preview does not show the new label: $t")
    check(!t.exists(_.contains("Range.split")), s"the preview still shows the old label: $t")
    check(t.exists(_.contains("Menlo,monospace")), s"the preview does not use the font of the label: $t")

  test("a node gets the node defaults of the diagram"):
    // The node `left` has no attributes of its own. Its shape comes from `node [...]`.
    val out = svg(NodeId("left"), "plain text")
    check(out.contains("<path"), s"the preview of a default node is not a rounded box:\n$out")

  test("a plain label keeps its quotes and its line breaks"):
    val t = texts(svg(NodeId("left"), "say \"hi\"\nsecond line"))
    check(t.exists(_.contains("say &quot;hi&quot;")) || t.exists(_.contains("say \"hi\"")), s"the quotes are lost: $t")
    check(t.exists(_.contains("second line")), s"the second line is lost: $t")

  test("an arrow label previews with the font of the arrow, with no box"):
    val out = svg(arrowId, "right: Range[2, 3]")
    val t   = texts(out)
    val label = t.find(_.contains("right: Range[2, 3]"))
    check(label.isDefined, s"the preview does not show the arrow label: $t")
    check(label.exists(_.contains("Menlo,monospace")), s"the arrow label does not use the arrow font: $label")
    check(label.exists(_.contains("font-size=\"11.00\"")), s"the arrow label does not use the arrow font size: $label")
    check(!out.contains("<path") && !out.contains("<polygon fill=\"#"), s"the arrow label preview has a box:\n$out")

  test("a group label previews with the font of the group"):
    val t     = texts(svg(groupId, "All steps"))
    val label = t.find(_.contains("All steps"))
    check(label.isDefined, s"the preview does not show the group label: $t")
    check(label.exists(_.contains("Georgia")), s"the group label does not use the group font: $label")
    check(label.exists(_.contains("font-size=\"18.00\"")), s"the group label does not use the group font size: $label")
