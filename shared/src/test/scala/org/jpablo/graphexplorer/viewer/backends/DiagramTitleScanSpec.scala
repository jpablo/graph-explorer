package org.jpablo.graphexplorer.viewer.backends

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotSourceScan
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidSourceScan

/** The source-text title scanners behind the "show the diagram's own title instead of
  * Untitled" feature. They run per keystroke and per library card, so they are regex/
  * line-level by design — these tests pin the recognized forms and, as importantly,
  * the forms deliberately NOT recognized.
  */
class DiagramTitleScanSpec extends FunSuite:

  // ---------------- Mermaid ----------------

  test("mermaid: standalone title line (C4, gantt, timeline, ...)"):
    val text = """C4Context
                 |  title Graph Explorer context
                 |  Person(user, "User", "Edits diagrams")""".stripMargin
    assertEquals(MermaidSourceScan.diagramTitle(text), Some("Graph Explorer context"))

  test("mermaid: YAML frontmatter title"):
    val text = """---
                 |title: Animal example
                 |---
                 |flowchart LR
                 |  a --> b""".stripMargin
    assertEquals(MermaidSourceScan.diagramTitle(text), Some("Animal example"))

  test("mermaid: quoted xychart title loses its quotes"):
    val text = """xychart-beta
                 |  title "Sales Revenue"
                 |  x-axis [jan, feb]""".stripMargin
    assertEquals(MermaidSourceScan.diagramTitle(text), Some("Sales Revenue"))

  test("mermaid: a flowchart node named `title` is NOT a title"):
    val text = """flowchart LR
                 |  title --> b""".stripMargin
    assertEquals(MermaidSourceScan.diagramTitle(text), None)

  test("mermaid: no title anywhere"):
    assertEquals(MermaidSourceScan.diagramTitle("sequenceDiagram\n  A->>B: hi"), None)

  // ---------------- DOT ----------------

  test("dot: bare graph-level label statement"):
    val text = """digraph "G" {
                 |  label = "My Pipeline";
                 |  a -> b;
                 |}""".stripMargin
    assertEquals(DotSourceScan.graphTitle(text), Some("My Pipeline"))

  test("dot: label inside a graph attribute statement"):
    val text = """digraph "G" {
                 |  graph [rankdir="LR", label="Build Steps"];
                 |  a -> b;
                 |}""".stripMargin
    assertEquals(DotSourceScan.graphTitle(text), Some("Build Steps"))

  test("dot: a cluster's label is NOT the graph title"):
    val text = """digraph "G" {
                 |  subgraph cluster_0 {
                 |    label = "Group A";
                 |    a;
                 |  }
                 |}""".stripMargin
    assertEquals(DotSourceScan.graphTitle(text), None)

  test("dot: a node's label attribute is NOT the graph title"):
    val text = """digraph "G" {
                 |  "a" [label="Node A"];
                 |}""".stripMargin
    assertEquals(DotSourceScan.graphTitle(text), None)

  test("dot: empty label is no title"):
    assertEquals(DotSourceScan.graphTitle("""digraph G { label = ""; }"""), None)

  /** The multi-line attribute form is the trap: a node's label stands alone on its
    * line, looking exactly like a top-level `label = "..."` statement. This is the
    * shape our own fsm example uses, and it made an untitled graph display as "LR_0".
    */
  test("dot: a node's label in a multi-line attribute list is NOT the graph title"):
    val text = """digraph "finite_state_machine" {
                 |  graph [rankdir="LR"];
                 |  node [shape="circle"];
                 |  "LR_0" [
                 |      peripheries="2",
                 |      label="LR_0"
                 |  ];
                 |  "LR_0" -> "LR_1" [label="SS(S)"];
                 |}""".stripMargin
    assertEquals(DotSourceScan.graphTitle(text), None)

  test("dot: the graph's own label in a multi-line attribute list IS the title"):
    val text = """digraph "G" {
                 |  graph [
                 |      rankdir="LR",
                 |      label="Real Title"
                 |  ];
                 |  "a" [
                 |      label="Node A"
                 |  ];
                 |}""".stripMargin
    assertEquals(DotSourceScan.graphTitle(text), Some("Real Title"))

  test("dot: a bracket inside a label does not unbalance the depth tracking"):
    val text = """digraph "G" {
                 |  "a" [label="arr[0]"];
                 |  label = "After The Brackets";
                 |}""".stripMargin
    assertEquals(DotSourceScan.graphTitle(text), Some("After The Brackets"))
