package org.jpablo.graphexplorer.viewer.backends

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph as dotJsonToViewerGraph}
import org.jpablo.graphexplorer.viewer.backends.mermaid.{MermaidFlowchartParser, toViewerGraph as mermaidToViewerGraph}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import org.jpablo.graphexplorer.viewer.models.AttributeId
import upickle.default.read

/** The DOT text of a graph from a Mermaid source (ADR 0002).
  *
  * The Graphviz renderer can draw a Mermaid source. It prints the graph as DOT
  * text, and Graphviz must accept that text.
  *
  * Review finding. The Mermaid reader keeps each class definition as a graph
  * attribute `mermaid_classDef_<name>`, and the DOT writer wrote each key with
  * no quotes. A `-` is not a character of a DOT id with no quotes. Thus
  * Graphviz refused the text of a class name such as `my-class`, and the
  * canvas was empty with no message.
  */
class MermaidGraphToDotSpec extends FunSuite:

  private def dotOf(mermaid: String): String =
    val graph = MermaidFlowchartParser.parse(mermaid).fold(e => fail(s"could not parse $mermaid: $e"), identity)
    viewerGraphToText(mermaidToViewerGraph(graph), omitInternal = false)

  test("the DOT text of a Mermaid graph with a class name that has a hyphen is valid DOT"):
    val dot = dotOf("flowchart LR\n  classDef my-class fill:#f96\n  A:::my-class --> B\n")
    assert(dot.contains("my-class"), s"precondition: the DOT text has the class definition:\n$dot")
    ScalaGraphviz.structureJson(dot).fold(e => fail(s"Graphviz refuses the DOT text: $e\n$dot"), _ => ())

  test("control: the DOT text of a Mermaid graph with a class name that has no hyphen is valid DOT"):
    // Proof that the test above fails because of the hyphen, and not because of the class definition.
    val dot = dotOf("flowchart LR\n  classDef mine fill:#f96\n  A:::mine --> B\n")
    ScalaGraphviz.structureJson(dot).fold(e => fail(s"Graphviz refuses the DOT text: $e\n$dot"), _ => ())

  test("the DOT reader gives back the quoted key of a class name with a hyphen, with the same name and value"):
    val dot   = dotOf("flowchart LR\n  classDef my-class fill:#f96\n  A:::my-class --> B\n")
    val json  = ScalaGraphviz.structureJson(dot).fold(e => fail(s"Graphviz refuses the DOT text: $e\n$dot"), identity)
    val graph = dotJsonToViewerGraph(read[SimpleGraph](json))
    assertEquals(
      graph.elements.graphAttributes.get(AttributeId("mermaid_classDef_my-class")).map(_.toString),
      Some("fill:#f96"),
      s"the graph attributes: ${graph.elements.graphAttributes}"
    )

  test("the DOT writer quotes only a key that is not a DOT id with no quotes"):
    // The usual keys keep the text of before.
    val dot = dotOf("flowchart LR\n  classDef mine fill:#f96\n  A:::mine --> B\n")
    assert(dot.contains("mermaid_classDef_mine=") && !dot.contains("\"mermaid_classDef_mine\""), dot)
