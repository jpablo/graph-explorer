package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite

class MermaidClassDefFallbackSpec extends FunSuite:

  test("extractClassDefsFromText should parse classDef declarations including default"):
    val source =
      """flowchart LR
        |classDef default fill:#fefecc,stroke:#85df72,color:#111
        |classDef pink fill:#ff66cc,stroke:#aa0099
        |A --> B
        |""".stripMargin

    val parsed = MermaidClassDefFallback.extractClassDefsFromText(source)
    assert(parsed.contains("default"))
    assert(parsed.contains("pink"))
    assertEquals(parsed("default").styles, List("fill:#fefecc", "stroke:#85df72", "color:#111"))
    assertEquals(parsed("pink").styles, List("fill:#ff66cc", "stroke:#aa0099"))

  test("withSourceClassDefs should inject parsed defaults/classes when missing"):
    val source =
      """flowchart LR
        |classDef default fill:#fefecc,stroke:#85df72
        |classDef pink fill:#ff66cc,stroke:#aa0099
        |A --> B
        |""".stripMargin

    val merged = MermaidClassDefFallback.withSourceClassDefs(
      source,
      classDefs = Map.empty
    )

    assert(merged.contains("default"))
    assert(merged.contains("pink"))
    assertEquals(merged("default").styles, List("fill:#fefecc", "stroke:#85df72"))

  test("withSourceClassDefs should keep existing parser classDef values when present"):
    val source =
      """flowchart LR
        |classDef default fill:#fefecc,stroke:#85df72
        |A --> B
        |""".stripMargin

    val merged = MermaidClassDefFallback.withSourceClassDefs(
      source,
      classDefs = Map("default" -> MermaidClassDef(styles = List("fill:#111111")))
    )

    assertEquals(merged("default").styles, List("fill:#111111"))
