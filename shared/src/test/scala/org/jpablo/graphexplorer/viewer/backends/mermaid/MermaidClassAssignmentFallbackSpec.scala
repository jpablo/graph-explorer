package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite

class MermaidClassAssignmentFallbackSpec extends FunSuite:

  test("extractClassAssignmentsFromText should parse class assignments for nodes/subgraphs"):
    val source =
      """flowchart LR
        |subgraph G1 [Service Layer]
        |  A[CodeMirror]
        |  B[Parser]
        |end
        |class G1 pink
        |class A,B pink,active
        |""".stripMargin

    val parsed = MermaidClassAssignmentFallback.extractClassAssignmentsFromText(source)
    assertEquals(parsed("G1"), List("pink"))
    assertEquals(parsed("A"), List("pink", "active"))
    assertEquals(parsed("B"), List("pink", "active"))

  test("withSourceClassAssignments should inject missing classes from source"):
    val source =
      """flowchart LR
        |A --> B
        |class G1 pink
        |class A pink
        |""".stripMargin

    val parserVertices = Map(
      "A" -> MermaidVertex(id = "A", text = "A"),
      "B" -> MermaidVertex(id = "B", text = "B")
    )
    val parserSubgraphs = List(
      MermaidSubgraph(id = "G1", title = Some("Service Layer"), nodes = List("A", "B"))
    )

    val (mergedVertices, mergedSubgraphs) = MermaidClassAssignmentFallback.withSourceClassAssignments(source, parserVertices, parserSubgraphs)

    assertEquals(mergedVertices("A").classes, List("pink"))
    assertEquals(mergedVertices("B").classes, Nil)
    assertEquals(mergedSubgraphs.head.classes, List("pink"))

  test("withSourceClassAssignments should preserve parser class assignments and merge missing source classes"):
    val source =
      """flowchart LR
        |A --> B
        |class A pink
        |""".stripMargin

    val parserVertices = Map(
      "A" -> MermaidVertex(id = "A", text = "A", classes = List("parserClass")),
      "B" -> MermaidVertex(id = "B", text = "B")
    )

    val (mergedVertices, _) = MermaidClassAssignmentFallback.withSourceClassAssignments(source, parserVertices, Nil)

    assertEquals(mergedVertices("A").classes, List("parserClass", "pink"))

  test("withSourceClassAssignments should resolve source classes by either parser key or vertex.id"):
    val source =
      """flowchart LR
        |A --> B
        |class A pink
        |""".stripMargin

    val parserVertices = Map(
      "flowchart-A-0" -> MermaidVertex(id = "A", text = "A"),
      "flowchart-B-0" -> MermaidVertex(id = "B", text = "B")
    )

    val (mergedVertices, _) = MermaidClassAssignmentFallback.withSourceClassAssignments(source, parserVertices, Nil)

    assertEquals(mergedVertices("flowchart-A-0").classes, List("pink"))
    assertEquals(mergedVertices("flowchart-B-0").classes, Nil)
