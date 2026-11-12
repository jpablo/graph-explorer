package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Style}
import org.jpablo.graphexplorer.viewer.models.{AttributeId, GroupId, NodeId}

class MermaidFlowchartParserSpec extends FunSuite:

  private val representative =
    """%% a comment before the header
      |flowchart LR
      |  subgraph outer["Outer group"]
      |    direction TB
      |    A["Alpha<br/>line"]
      |    subgraph inner["Inner group"]
      |      B["Beta"]
      |    end
      |  end
      |  C["Gamma"]
      |
      |  A -->|"plain label"| B
      |  B -.->|"dashed label"| C
      |  C <-->|"both ways"| A
      |
      |  classDef active fill:#eef,stroke:#225,color:#111
      |  class A,B active
      |""".stripMargin

  test("headless parser reads representative Mermaid flowchart syntax"):
    val parsed = MermaidFlowchartParser.parse(representative).fold(fail(_), identity)
    val graph  = toViewerGraph(parsed)

    assertEquals(graph.nodeIds, Set(NodeId("A"), NodeId("B"), NodeId("C")))
    assertEquals(graph.arrows.size, 3)
    assertEquals(graph.groups.keySet, Set(GroupId("outer"), GroupId("inner")))
    assertEquals(graph.memberships.get(NodeId("A")), Some(GroupId("outer")))
    assertEquals(graph.memberships.get(GroupId("inner")), Some(GroupId("outer")))
    assertEquals(graph.memberships.get(NodeId("B")), Some(GroupId("inner")))

    assertEquals(graph.nodes(NodeId("A")).attributes.get(Label.attrId).map(_.toString), Some("Alpha\\nline"))
    assertEquals(graph.groups(GroupId("outer")).attributes.get(Label.attrId).map(_.toString), Some("Outer group"))
    assertEquals(graph.nodes(NodeId("A")).attributes.get(AttributeId("mermaid_class")).map(_.toString), Some("active"))
    assertEquals(graph.arrows.values.count(_.attributes.get(Style.attrId).exists(_.toString == "dashed")), 1)

  test("headless parser rejects non-flowchart Mermaid kinds"):
    val result = MermaidFlowchartParser.parse("sequenceDiagram\n  Alice->>Bob: hello\n")
    assert(result.left.exists(_.contains("expected a flowchart header")), result)

  test("headless parser reads each flowchart header that real Mermaid reads, with the direction of Mermaid"):
    // Real Mermaid 11.12 reads each header below, and `getDirection()` gives the direction on the right. A header with
    // no direction gives TB. Mermaid changes `>` to LR, `<` to RL, `^` to BT and `v` to TB, and it keeps BR. The five
    // letter forms keep the letters of the text, as before. (Mermaid itself gives TB for TD.)
    val headersAndDirections = Vector(
      "graph"        -> "TB",
      "graph "       -> "TB",
      "flowchart"    -> "TB",
      "graph >"      -> "LR",
      "graph>"       -> "LR",
      "graph <"      -> "RL",
      "graph ^"      -> "BT",
      "graph v"      -> "TB",
      "graph BR"     -> "BR",
      "graph >;"     -> "LR",
      "graph\tTD"    -> "TD",
      "flowchart >"  -> "LR",
      "flowchart v"  -> "TB",
      "graph LR"     -> "LR",
      "flowchart TD" -> "TD"
    )
    val obtained = headersAndDirections.map: (header, _) =>
      header -> MermaidFlowchartParser.parse(s"$header\n  A --> B").map(g => (g.direction, g.vertices.keySet))
    val expected = headersAndDirections.map: (header, direction) =>
      header -> (Right((Some(direction), Set("A", "B"))): Either[String, (Option[String], Set[String])])
    assertEquals(obtained, expected)

  test("headless parser rejects the flowchart headers that real Mermaid rejects"):
    // Real Mermaid 11.12 gives a lexical error or a parse error for each header below.
    val headers = Vector("graph;", "flowchart;", "graph lr", "graph V", "graph vv", "graph v1", "graph LR1", "graph ><", "graph >x")
    val accepted = headers.filterNot(h => MermaidFlowchartParser.parse(s"$h\n  A --> B").left.exists(_.contains("expected a flowchart header")))
    assertEquals(accepted, Vector.empty)

  test("headless parser reports unclosed nested subgraphs"):
    val result = MermaidFlowchartParser.parse("flowchart LR\n  subgraph open[Open]\n    A\n")
    assert(result.left.exists(_.contains("no matching 'end'")), result)
