package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite

import scala.collection.immutable.VectorMap

class MermaidStyleDeclarationsSpec extends FunSuite:

  test("parse should normalize key casing and trim whitespace"):
    val parsed = MermaidStyleDeclarations.parse(" fill : #f9f , stroke : #333 , font-family : Times New Roman ")

    assertEquals(
      parsed,
      VectorMap(
        "fill" -> "#f9f",
        "stroke" -> "#333",
        "font-family" -> "Times New Roman"
      )
    )

  test("parse should keep last declaration for duplicated keys"):
    val parsed = MermaidStyleDeclarations.parse("fill:#aaa, stroke:#111, fill:#bbb")

    assertEquals(parsed.get("fill"), Some("#bbb"))
    assertEquals(parsed.get("stroke"), Some("#111"))

  test("parse should ignore malformed fragments safely"):
    val parsed = MermaidStyleDeclarations.parse("fill:#aaa, malformed , :no-key,stroke:#111")

    assertEquals(
      parsed,
      VectorMap(
        "fill" -> "#aaa",
        "stroke" -> "#111"
      )
    )

  test("parse should support fragments input"):
    val parsed = MermaidStyleDeclarations.parse(Seq(" fill:#abc ", "stroke-width: 3px", "invalid"))

    assertEquals(
      parsed,
      VectorMap(
        "fill" -> "#abc",
        "stroke-width" -> "3px"
      )
    )
