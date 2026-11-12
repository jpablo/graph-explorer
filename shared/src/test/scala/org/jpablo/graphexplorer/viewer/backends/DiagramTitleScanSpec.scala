package org.jpablo.graphexplorer.viewer.backends

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.Graphviz as ScalaGraphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotSourceScan
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{SimpleGraph, toViewerGraph as dotJsonToViewerGraph}
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidSourceScan
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import upickle.default.read

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

  // The two tests below show two more effects of the header bugs. `diagramTitle`
  // finds the header with its own scan. That scan does not skip the frontmatter.
  // It also knows `graph` only when a space follows it.
  test("mermaid: a node named `title` is NOT a title when the frontmatter has no `title:`"):
    val text = """---
                 |config:
                 |  theme: dark
                 |---
                 |flowchart LR
                 |  title --> b""".stripMargin
    assertEquals(MermaidSourceScan.diagramTitle(text), None)

  test("mermaid: a node named `title` is NOT a title in a flowchart with a bare `graph` header"):
    assertEquals(MermaidSourceScan.diagramTitle("graph\n  title --> b"), None)

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

  // ---------------- DOT: an escaped quote ----------------

  /** The graph label that the DOT reader of the app gives. The canvas and the
    * attribute panel show this label. The reader changes `\"` in a quoted string
    * into `"`, as Graphviz does.
    */
  private def readerGraphLabel(dot: String): Option[String] =
    ScalaGraphviz.structureJson(dot) match
      case Right(json) =>
        dotJsonToViewerGraph(read[SimpleGraph](json)).elements.graphAttributes.get(Label.attrId).map(_.toString)
      case Left(err) => fail(s"setup: the DOT reader rejects the fixture: $err\n$dot")

  private def dotTitle(text: String): Option[String] = SourceTitle.of(DiagramFormat.DOT, text)

  test("dot: an escaped quote in the graph label stays a quote in the title"):
    val forms = Vector(
      "label statement" ->
        """digraph G {
          |  label = "The \"core\" plan";
          |  a -> b;
          |}""".stripMargin,
      "graph attribute statement" ->
        """digraph G {
          |  graph [label="The \"core\" plan"];
          |  a -> b;
          |}""".stripMargin,
      "multi-line graph attribute list" ->
        """digraph G {
          |  graph [
          |      rankdir="LR",
          |      label="The \"core\" plan"
          |  ];
          |  a -> b;
          |}""".stripMargin
    )
    forms.foreach((name, text) => assertEquals(readerGraphLabel(text), Some("The \"core\" plan"), s"setup: $name"))
    assertEquals(
      forms.map((name, text) => name -> dotTitle(text)),
      forms.map((name, _) => name -> Some("The \"core\" plan"))
    )

  test("dot: an escaped quote and a bracket in a node label do not hide the graph title"):
    // The same cause as the test above: the scan does not know `\"`. Thus it sees
    // `[` outside a quoted span, and it thinks that the attribute list stays open.
    val text = """digraph G {
                 |  a [label="say \"[\" here"];
                 |  label = "Title";
                 |}""".stripMargin
    assertEquals(readerGraphLabel(text), Some("Title"), "setup")
    assertEquals(dotTitle(text), Some("Title"))

  test("dot: a backslash before the closing quote does not escape it"):
    // A guard for the fix of `\"`. It passes before the fix. In `"C:\\"`, the
    // backslash escapes the backslash, and the quote closes the string.
    val text = """digraph G {
                 |  graph [label="C:\\", rankdir="LR"];
                 |  a -> b;
                 |}""".stripMargin
    val title = dotTitle(text)
    assert(title.exists(t => t.startsWith("C:") && !t.contains("rankdir")), s"title: $title")
    assertEquals(title, readerGraphLabel(text), "the title is not the label that the DOT reader gives")
