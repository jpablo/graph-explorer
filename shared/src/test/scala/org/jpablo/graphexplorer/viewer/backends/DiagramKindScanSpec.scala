package org.jpablo.graphexplorer.viewer.backends

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotSourceScan
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidSourceScan

/** The source-text kind scanners behind the library's diagram-kind badge. Like the
  * title scanners, they run per library card and are line-level by design — these
  * tests pin the recognized forms and the forms deliberately NOT recognized.
  */
class DiagramKindScanSpec extends FunSuite:

  // ---------------- Mermaid ----------------

  test("mermaid: flowchart, in both spellings"):
    assertEquals(MermaidSourceScan.diagramKind("flowchart TD\n  a --> b"), Some("flowchart"))
    assertEquals(MermaidSourceScan.diagramKind("graph LR\n  a --> b"), Some("flowchart"))

  test("mermaid: header keyword maps to a display name"):
    assertEquals(MermaidSourceScan.diagramKind("sequenceDiagram\n  A->>B: hi"), Some("sequence"))
    assertEquals(MermaidSourceScan.diagramKind("classDiagram\n  A <|-- B"), Some("class"))
    assertEquals(MermaidSourceScan.diagramKind("stateDiagram-v2\n  [*] --> S"), Some("state"))
    assertEquals(MermaidSourceScan.diagramKind("erDiagram\n  A ||--o{ B : has"), Some("ER"))
    assertEquals(MermaidSourceScan.diagramKind("C4Context\n  title x"), Some("C4"))
    assertEquals(MermaidSourceScan.diagramKind("xychart-beta\n  title \"x\""), Some("xy chart"))

  test("mermaid: the header may follow frontmatter and directives"):
    val text = """---
                 |title: Animal example
                 |---
                 |%%{init: {"theme": "dark"}}%%
                 |flowchart LR
                 |  a --> b""".stripMargin
    assertEquals(MermaidSourceScan.diagramKind(text), Some("flowchart"))

  test("mermaid: an unknown header is no kind"):
    assertEquals(MermaidSourceScan.diagramKind("something else entirely"), None)

  test("mermaid: `graph <dir>` yields to DOT when the text is a DOT declaration"):
    // Same rule that keeps DiagramFormat from calling these Mermaid — asserted
    // here too because THIS is the scanner that decides, and the library card's
    // kind badge reads it directly.
    assertEquals(MermaidSourceScan.diagramKind("graph LR { a -- b }"), None)
    assertEquals(MermaidSourceScan.diagramKind("graph TD\n{\n  a -- b\n}"), None)
    assertEquals(MermaidSourceScan.diagramKind("graph LRX\n  A --> B"), None)
    assertEquals(MermaidSourceScan.diagramKind("graph LR; A{Decision} --> B"), Some("flowchart"))

  test("mermaid: frontmatter with no diagram after it is no kind"):
    assertEquals(MermaidSourceScan.diagramKind("---\ntitle: notes\n---\njust prose"), None)

  // ---------------- DOT ----------------

  test("dot: digraph vs graph, case-independent"):
    assertEquals(DotSourceScan.diagramKind("digraph G { a -> b }"), Some("digraph"))
    assertEquals(DotSourceScan.diagramKind("graph G { a -- b }"), Some("graph"))
    assertEquals(DotSourceScan.diagramKind("DIGRAPH G { a -> b }"), Some("digraph"))

  test("dot: strict prefix is accepted, kind stays the keyword"):
    assertEquals(DotSourceScan.diagramKind("strict digraph G { a -> b }"), Some("digraph"))

  test("dot: comment lines before the declaration are skipped"):
    val text = """// my graph definition
                 |digraph G {
                 |  a -> b;
                 |}""".stripMargin
    assertEquals(DotSourceScan.diagramKind(text), Some("digraph"))

  test("dot: an identifier merely starting with the keyword is NOT a declaration"):
    assertEquals(DotSourceScan.diagramKind("graph_config = 1"), None)
