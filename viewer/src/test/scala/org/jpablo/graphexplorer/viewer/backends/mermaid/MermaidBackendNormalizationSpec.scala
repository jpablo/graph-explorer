package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import scala.scalajs.js

class MermaidBackendNormalizationSpec extends FunSuite:

  test("extractDefaultEdgeMarkerColor should prefer stroke from linkStyle default") {
    val source =
      """flowchart LR
        |A --> B
        |linkStyle default stroke:#0044ff,stroke-width:2px,color:#663300,font-size:12px
        |linkStyle 0 stroke:#ff0000,stroke-width:3px
        |""".stripMargin

    assertEquals(MermaidBackend.extractDefaultEdgeMarkerColor(source), Some("#0044ff"))
  }

  test("extractDefaultEdgeMarkerColor should fallback to color when stroke is absent") {
    val source =
      """flowchart LR
        |A --> B
        |linkStyle default color:#663300,font-size:12px
        |""".stripMargin

    assertEquals(MermaidBackend.extractDefaultEdgeMarkerColor(source), Some("#663300"))
  }

  test("withImportantDeclarations should add !important and keep existing markers") {
    val styleText = "stroke:#00aa00;stroke-width:4px;fill:#ff99dd !important;font-size:20px"

    val normalized = MermaidBackend.withImportantDeclarations(styleText)

    assertEquals(
      normalized,
      "stroke:#00aa00 !important;stroke-width:4px !important;fill:#ff99dd !important;font-size:20px !important"
    )
  }

  test("firstCssDeclarationValue should return the first declaration for repeated keys") {
    val styleText = "stroke:#0044ff;stroke-width:2px;stroke:#ff0000"
    assertEquals(MermaidBackend.firstCssDeclarationValue(styleText, "stroke"), Some("#0044ff"))
  }

  test("extractNodeInlineTextDeclarations should expose inline text attrs and mirror color to fill") {
    val styleText = "stroke:#00aa00;stroke-width:4px;fill:#ff99dd;font-size:20px;color:#ffffff;font-family:Verdana"

    assertEquals(
      MermaidBackend.extractNodeInlineTextDeclarations(styleText),
      List(
        "font-size"   -> "20px",
        "color"       -> "#ffffff",
        "font-family" -> "Verdana",
        "fill"        -> "#ffffff"
      )
    )
  }

  test("hasFlowchartAccessors should detect valid DiagramYY accessor shape") {
    val emptyYy = js.Dynamic.literal().asInstanceOf[DiagramYY]
    val goodYy = js.Dynamic.literal(
      getVertices = (() => js.Dictionary.empty[MermaidVertexJS])
    ).asInstanceOf[DiagramYY]

    assertEquals(MermaidBackend.hasFlowchartAccessors(emptyYy), false)
    assertEquals(MermaidBackend.hasFlowchartAccessors(goodYy), true)
  }
