package org.jpablo.graphexplorer.viewer.backends.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global

/** An HTML label of a DOT group (cluster) keeps its HTML form.
  *
  * A group label such as `label=<<b>name</b><br/>path>;` showed its markup as
  * text on the canvas: "<b>name</b><br/>…". The canvas reads the source into a
  * graph, writes the graph as DOT again (the visible graph), and Graphviz draws
  * that text. These tests follow the same path.
  */
class ClusterHtmlLabelSpec extends FunSuite with TestHelpers:

  /** In the Playwright runner, a munit failure arrives with an empty message. */
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then
      println(s"[ClusterHtmlLabelSpec] $clue")
      throw new AssertionError(clue)

  /** As in a real file: the label is a statement in the body of the group. */
  private val statementForm =
    """digraph G {
      |  subgraph cluster_discovery {
      |    label=<<b>agent-skills::discovery</b><br/><font point-size="9">rust/agent-skills/src/discovery.rs</font>>;
      |    labeljust=l;
      |    a; b;
      |  }
      |  a -> b;
      |}""".stripMargin

  /** The same label in a `graph [...]` attribute list. */
  private val attrListForm =
    """digraph G {
      |  subgraph cluster_discovery {
      |    graph [label=<<b>agent-skills::discovery</b><br/><font point-size="9">rust/agent-skills/src/discovery.rs</font>>];
      |    a; b;
      |  }
      |  a -> b;
      |}""".stripMargin

  private def roundTrip(name: String, source: String) =
    withGraphvizAsync: graphviz =>
      DotExtractor(graphviz).textToGraph(source).map: graph =>
        val printed = DotPrinter.graphToText(graph, omitInternal = false)
        check(
          printed.contains("label=<<b>agent-skills::discovery</b>"),
          s"$name: the printed DOT does not keep the HTML label of the group:\n$printed"
        )
        val svg   = graphviz.textToSvgOnly(DotText(printed)).get
        val texts = svg.querySelectorAll("g.cluster text")
        val drawn = (0 until texts.length).map(texts(_).textContent).mkString(" | ")
        check(drawn.contains("agent-skills::discovery"), s"$name: the group title is not drawn: $drawn")
        check(!drawn.contains("<b>") && !drawn.contains("<br"), s"$name: the group title shows its markup as text: $drawn")

  test("an HTML group label in statement form keeps its HTML form"):
    roundTrip("statement form", statementForm)

  test("an HTML group label in a graph attribute list keeps its HTML form"):
    roundTrip("attribute list form", attrListForm)

  /** As in the file "agent-skills :: discovery.rs — UML class diagram": the
    * group has a bold font, and its HTML label has a `<b>` run. The canvas did
    * not show the diagram, because the SVG had `font-weight` two times in one
    * element, and the SVG did not parse.
    */
  private val boldFontForm =
    """digraph G {
      |  subgraph cluster_discovery {
      |    fontname="Helvetica-Bold";
      |    label=<<b>agent-skills::discovery</b><br/><font point-size="9">rust/agent-skills/src/discovery.rs</font>>;
      |    labeljust=l;
      |    a; b;
      |  }
      |  a -> b;
      |}""".stripMargin

  test("an HTML group label with a bold font gives a drawing on the canvas"):
    withGraphvizAsync: graphviz =>
      DotExtractor(graphviz).textToGraph(boldFontForm).map: graph =>
        // The same call as GraphvizRenderer.render, the path of the canvas.
        val drawn = graphviz.textToSvg(DotText(DotPrinter.graphToText(graph, omitInternal = false)))
        check(drawn.isSuccess, s"the canvas gets no drawing: ${drawn.failed.toOption.map(_.getMessage)}")
        val texts = drawn.get.svg.ref.querySelectorAll("g.cluster text")
        val shown = (0 until texts.length).map(texts(_).textContent).mkString(" | ")
        check(shown.contains("agent-skills::discovery"), s"the group title is not drawn: $shown")

  /** As in the same UML file: the multiplicities are `headlabel` and
    * `taillabel` attributes. The canvas did not show them.
    */
  private val edgeEndLabels =
    """digraph G {
      |  a -> b [headlabel="0..*", taillabel="owner"];
      |  c -> d [dir=back, arrowtail=diamond, arrowhead=none, headlabel="1"];
      |}""".stripMargin

  test("the labels at the ends of an edge are drawn on the canvas"):
    withGraphvizAsync: graphviz =>
      DotExtractor(graphviz).textToGraph(edgeEndLabels).map: graph =>
        val printed = DotPrinter.graphToText(graph, omitInternal = false)
        check(printed.contains("headlabel") && printed.contains("taillabel"),
          s"the printed DOT does not keep the end labels:\n$printed")
        val drawn = graphviz.textToSvg(DotText(printed))
        check(drawn.isSuccess, s"the canvas gets no drawing: ${drawn.failed.toOption.map(_.getMessage)}")
        val texts = drawn.get.svg.ref.querySelectorAll("g.edge text")
        val shown = (0 until texts.length).map(texts(_).textContent).toSet
        check(Set("0..*", "owner", "1").subsetOf(shown), s"the edges show only: $shown")
