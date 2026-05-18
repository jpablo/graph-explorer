package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite

class MermaidEdgeLabelFallbackSpec extends FunSuite:

  test("extractEdgeLabelsFromText should parse pipe-notation labels"):
    val source =
      """flowchart TB
        |  A -->|yes| B
        |  B -->|no| C
        |""".stripMargin

    val labels = MermaidEdgeLabelFallback.extractEdgeLabelsFromText(source)

    assertEquals(labels.get(("A", "B", 1)), Some("yes"))
    assertEquals(labels.get(("B", "C", 1)), Some("no"))

  test("extractEdgeLabelsFromText should parse inline-notation labels"):
    val source =
      """flowchart TB
        |  A -- approve --> B
        |  B -- reject --> C
        |""".stripMargin

    val labels = MermaidEdgeLabelFallback.extractEdgeLabelsFromText(source)

    assertEquals(labels.get(("A", "B", 1)), Some("approve"))
    assertEquals(labels.get(("B", "C", 1)), Some("reject"))

  test("extractEdgeLabelsFromText should track per-pair ordinal for parallel edges"):
    val source =
      """flowchart TB
        |  A -->|first| B
        |  A -->|second| B
        |""".stripMargin

    val labels = MermaidEdgeLabelFallback.extractEdgeLabelsFromText(source)

    assertEquals(labels.get(("A", "B", 1)), Some("first"))
    assertEquals(labels.get(("A", "B", 2)), Some("second"))

  test("extractEdgeLabelsFromText should ignore edges without labels"):
    val source =
      """flowchart TB
        |  A --> B
        |  B -->|yes| C
        |""".stripMargin

    val labels = MermaidEdgeLabelFallback.extractEdgeLabelsFromText(source)

    assertEquals(labels.get(("A", "B", 1)), None)
    assertEquals(labels.get(("B", "C", 1)), Some("yes"))

  test("extractEdgeLabelsFromText should ignore header and directive lines"):
    val source =
      """flowchart LR
        |  classDef foo fill:#f00
        |  style A fill:#f00
        |  linkStyle default stroke:#f00
        |  A -->|label| B
        |""".stripMargin

    val labels = MermaidEdgeLabelFallback.extractEdgeLabelsFromText(source)

    assertEquals(labels.get(("A", "B", 1)), Some("label"))
    assertEquals(labels.size, 1)

  test("withSourceEdgeLabels should inject missing labels from source"):
    val source =
      """flowchart TB
        |  A -->|yes| B
        |""".stripMargin

    val edges = List(MermaidEdge(start = "A", end = "B", text = None))

    val result = MermaidEdgeLabelFallback.withSourceEdgeLabels(source, edges)

    assertEquals(result.head.text, Some("yes"))

  test("withSourceEdgeLabels should keep parser label when already present"):
    val source =
      """flowchart TB
        |  A -->|yes| B
        |""".stripMargin

    val edges = List(MermaidEdge(start = "A", end = "B", text = Some("parser-label")))

    val result = MermaidEdgeLabelFallback.withSourceEdgeLabels(source, edges)

    assertEquals(result.head.text, Some("parser-label"))

  test("withSourceEdgeLabels should return edges unchanged when all have labels"):
    val source = "flowchart TB\n  A -->|yes| B\n"
    val edges  = List(MermaidEdge(start = "A", end = "B", text = Some("yes")))

    val result = MermaidEdgeLabelFallback.withSourceEdgeLabels(source, edges)

    assert(result eq edges, "should return the same list reference when no recovery needed")

  test("withSourceEdgeLabels should handle parallel edges by ordinal"):
    val source =
      """flowchart TB
        |  A -->|first| B
        |  A -->|second| B
        |""".stripMargin

    val edges = List(
      MermaidEdge(start = "A", end = "B", text = None),
      MermaidEdge(start = "A", end = "B", text = None)
    )

    val result = MermaidEdgeLabelFallback.withSourceEdgeLabels(source, edges)

    assertEquals(result(0).text, Some("first"))
    assertEquals(result(1).text, Some("second"))

  test("withSourceEdgeLabels should handle mixed: some edges have labels, some do not"):
    val source =
      """flowchart TB
        |  A -->|first| B
        |  A -->|second| B
        |""".stripMargin

    val edges = List(
      MermaidEdge(start = "A", end = "B", text = Some("parser-first")),
      MermaidEdge(start = "A", end = "B", text = None)
    )

    val result = MermaidEdgeLabelFallback.withSourceEdgeLabels(source, edges)

    assertEquals(result(0).text, Some("parser-first"), "parser label should be preserved")
    assertEquals(result(1).text, Some("second"), "second edge label should be recovered from source")

  test("extractEdgeLabelsFromText should parse dotted-arrow pipe labels"):
    val source =
      """flowchart TB
        |  A -.->|maybe| B
        |""".stripMargin

    val labels = MermaidEdgeLabelFallback.extractEdgeLabelsFromText(source)

    assertEquals(labels.get(("A", "B", 1)), Some("maybe"))

  test("extractEdgeLabelsFromText should parse thick-arrow pipe labels"):
    val source =
      """flowchart TB
        |  A ==>|strong| B
        |""".stripMargin

    val labels = MermaidEdgeLabelFallback.extractEdgeLabelsFromText(source)

    assertEquals(labels.get(("A", "B", 1)), Some("strong"))

  test("extractEdgeLabelsFromText should not match node declarations as edges"):
    val source =
      """flowchart TB
        |  A[Some label]
        |  B{Decision}
        |  A -->|ok| B
        |""".stripMargin

    val labels = MermaidEdgeLabelFallback.extractEdgeLabelsFromText(source)

    assertEquals(labels.size, 1)
    assertEquals(labels.get(("A", "B", 1)), Some("ok"))

  // normalizeLabel: a label wrapped in literal double quotes has them
  // stripped (Mermaid quotes labels with special characters).
  test("extractEdgeLabelsFromText should strip surrounding double quotes"):
    val source =
      """flowchart TB
        |  A -->|"quoted label"| B
        |""".stripMargin

    val labels = MermaidEdgeLabelFallback.extractEdgeLabelsFromText(source)

    assertEquals(labels.get(("A", "B", 1)), Some("quoted label"))

  // normalizeLabel: Mermaid's `#quot;` HTML-ish escape for an embedded
  // double quote is decoded back to `"` (and any outer quotes stripped).
  test("extractEdgeLabelsFromText should decode #quot; escapes"):
    val unquoted =
      """flowchart TB
        |  A -->|say #quot;hi#quot; now| B
        |""".stripMargin
    assertEquals(
      MermaidEdgeLabelFallback.extractEdgeLabelsFromText(unquoted).get(("A", "B", 1)),
      Some("say \"hi\" now")
    )

    val wrapped =
      """flowchart TB
        |  A -->|"#quot;q#quot;"| B
        |""".stripMargin
    assertEquals(
      MermaidEdgeLabelFallback.extractEdgeLabelsFromText(wrapped).get(("A", "B", 1)),
      Some("\"q\"")
    )
