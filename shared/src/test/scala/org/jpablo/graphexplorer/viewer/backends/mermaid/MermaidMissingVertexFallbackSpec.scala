package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite

class MermaidMissingVertexFallbackSpec extends FunSuite:

  test("withSourceVertices should synthesize missing vertices from references and source labels/classes"):
    val source =
      """flowchart LR
        |subgraph G1 [Service Layer]
        |  A[CodeMirror]
        |  B[Parser]
        |end
        |A -->|parses| B
        |class G1 pink
        |class A pink
        |""".stripMargin

    val merged = MermaidMissingVertexFallback.withSourceVertices(
      sourceText = source,
      vertices = Map.empty,
      edges = List(MermaidEdge(start = "A", end = "B", text = Some("parses"))),
      subgraphs = List(MermaidSubgraph(id = "G1", nodes = List("A", "B"), classes = List("pink")))
    )

    assert(merged.contains("A"))
    assert(merged.contains("B"))
    assertEquals(merged("A").text, "CodeMirror")
    assertEquals(merged("B").text, "Parser")
    assertEquals(merged("A").classes, List("pink"))
    assertEquals(merged("B").classes, Nil)

  test("withSourceVertices should not duplicate a referenced id already represented by vertex.id"):
    val source =
      """flowchart LR
        |A[CodeMirror]
        |B[Parser]
        |A --> B
        |""".stripMargin

    val merged = MermaidMissingVertexFallback.withSourceVertices(
      sourceText = source,
      vertices = Map(
        "flowchart-A-0" -> MermaidVertex(id = "A", text = "CodeMirror"),
        "flowchart-B-0" -> MermaidVertex(id = "B", text = "Parser")
      ),
      edges = List(MermaidEdge(start = "A", end = "B")),
      subgraphs = Nil
    )

    assertEquals(merged.keySet, Set("flowchart-A-0", "flowchart-B-0"))
