package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite

class MermaidVertexLabelFallbackSpec extends FunSuite:

  test("extractVertexLabelsFromText should parse node labels and ignore subgraph title"):
    val source =
      """flowchart LR
        |subgraph G1 [Service Layer]
        |  A[CodeMirror]
        |  B[Parser]
        |end
        |A -->|parses| B
        |""".stripMargin

    val labels = MermaidVertexLabelFallback.extractVertexLabelsFromText(source)

    assertEquals(labels.get("A"), Some("CodeMirror"))
    assertEquals(labels.get("B"), Some("Parser"))
    assertEquals(labels.get("G1"), None)

  test("withSourceVertexLabels should inject missing labels when parser returns id text"):
    val source =
      """flowchart LR
        |A[CodeMirror]
        |B[Parser]
        |""".stripMargin

    val parserVertices = Map(
      "A" -> MermaidVertex(id = "A", text = "A"),
      "B" -> MermaidVertex(id = "B", text = "B")
    )

    val merged = MermaidVertexLabelFallback.withSourceVertexLabels(source, parserVertices)

    assertEquals(merged("A").text, "CodeMirror")
    assertEquals(merged("B").text, "Parser")

  test("withSourceVertexLabels should keep parser label when already present"):
    val source =
      """flowchart LR
        |A[CodeMirror]
        |""".stripMargin

    val parserVertices = Map(
      "A" -> MermaidVertex(id = "A", text = "ParserLabel")
    )

    val merged = MermaidVertexLabelFallback.withSourceVertexLabels(source, parserVertices)

    assertEquals(merged("A").text, "ParserLabel")

  test("withSourceVertexLabels should resolve source label by either parser key or vertex.id"):
    val source =
      """flowchart LR
        |A[CodeMirror]
        |B[Parser]
        |A --> B
        |""".stripMargin

    val parserVertices = Map(
      "flowchart-A-0" -> MermaidVertex(id = "A", text = "A"),
      "flowchart-B-0" -> MermaidVertex(id = "B", text = "B")
    )

    val merged = MermaidVertexLabelFallback.withSourceVertexLabels(source, parserVertices)

    assertEquals(merged("flowchart-A-0").text, "CodeMirror")
    assertEquals(merged("flowchart-B-0").text, "Parser")

  test("extractVertexLabelsFromText should not match fragments inside quoted labels or edge labels"):
    // Reproduces the bug where regex matched inside quoted node labels and edge labels,
    // creating phantom vertices like `n`, `Future`, and `nEventBus`.
    val source =
      """flowchart TB
        |  STATE["state: Var[GraphState]\n{ text, viewerGraph,\nformat, lastOrigin }"]:::core
        |  BUS["textChangeBus:\nEventBus[(String, Format, Origin)]"]:::async
        |  FMS["flatMapSwitch\nbackendFor(format).textToGraph(text)"]:::async
        |  FMS -->|"Future[ViewerGraph]\n→ EventStream.fromFuture"| STATE
        |  STATE --> BUS
        |""".stripMargin

    val labels = MermaidVertexLabelFallback.extractVertexLabelsFromText(source)

    // Should extract the real node IDs
    assert(labels.contains("STATE"), "STATE should be extracted")
    assert(labels.contains("BUS"), "BUS should be extracted")
    assert(labels.contains("FMS"), "FMS should be extracted")

    // Should NOT extract phantom IDs from inside quoted labels or edge labels
    assert(!labels.contains("n"), "phantom 'n' from \\n{ inside STATE label should not be extracted")
    assert(!labels.contains("nEventBus"), "phantom 'nEventBus' from \\nEventBus[( inside BUS label should not be extracted")
    assert(!labels.contains("Future"), "phantom 'Future' from Future[ViewerGraph] inside edge label should not be extracted")
