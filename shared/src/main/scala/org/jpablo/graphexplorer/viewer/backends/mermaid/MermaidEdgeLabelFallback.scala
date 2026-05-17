package org.jpablo.graphexplorer.viewer.backends.mermaid

/** Recovers edge labels from raw Mermaid source text when the JS parser drops them from `getEdges().text`.
  *
  * Mermaid's parser occasionally returns edges without their label text, analogous to how it sometimes drops vertex
  * labels. This fallback mirrors `MermaidVertexLabelFallback`: parser-provided labels are always authoritative; only
  * edges with no label are candidates for source-text recovery.
  *
  * Supported label syntaxes:
  *   - Pipe notation: `A -->|label| B`, `A -.->`|label| B`, `A ==>|label| B`
  *   - Inline notation: `A -- label --> B`
  *
  * Labels are matched by `(source, target)` pair and their ordinal position in the source (1-based), which aligns with
  * the sequence counter used by `ToViewerGraph` when building `ArrowId` values.
  */
object MermaidEdgeLabelFallback:

  private val IgnoredLinePrefixes = List(
    "%%",
    "flowchart",
    "graph",
    "subgraph",
    "classdef",
    "class ",
    "style ",
    "linkstyle",
    "click ",
    "end"
  )

  // Matches pipe-label edges: A -->|label| B, A -.->`|label| B, A ==>|label| B
  // Arrow type is one or more non-whitespace, non-pipe characters immediately before the first |.
  private val PipeLabelPattern =
    raw"""([A-Za-z0-9_][A-Za-z0-9_-]*)\s+[^\s|]+\|([^|]+)\|\s+([A-Za-z0-9_][A-Za-z0-9_-]*)""".r

  // Matches inline-label edges: A -- label --> B
  private val InlineLabelPattern =
    raw"""([A-Za-z0-9_][A-Za-z0-9_-]*)\s+--\s+(.+?)\s+-->\s+([A-Za-z0-9_][A-Za-z0-9_-]*)""".r

  /** When Mermaid's parser drops edge labels from `getEdges()`, recover them from the raw source text.
    *
    * Parser-provided labels are always preferred. Recovery is applied only to edges whose `text` field is empty or
    * absent. Labels are matched by `(source, target)` pair and their 1-based ordinal among edges sharing that pair,
    * mirroring `ToViewerGraph`'s sequence counter.
    */
  def withSourceEdgeLabels(sourceText: String, edges: List[MermaidEdge]): List[MermaidEdge] =
    if edges.isEmpty || edges.forall(_.text.exists(_.nonEmpty)) then edges
    else
      val sourceLabels = extractEdgeLabelsFromText(sourceText)
      val edgeCounts   = scala.collection.mutable.Map[(String, String), Int]().withDefaultValue(0)
      edges.map { edge =>
        val key = (edge.start, edge.end)
        edgeCounts(key) += 1
        val seq = edgeCounts(key)
        if edge.text.exists(_.nonEmpty) then edge
        else
          sourceLabels
            .get((edge.start, edge.end, seq))
            .fold(edge)(label => edge.copy(text = Some(label)))
      }

  /** Parses edge labels from Mermaid source text.
    *
    * Returns a map from `(source, target, ordinal)` to label string. Ordinal is 1-based and counts how many edges with
    * the same `(source, target)` pair have appeared so far in source order.
    */
  def extractEdgeLabelsFromText(sourceText: String): Map[(String, String, Int), String] =
    val result = scala.collection.mutable.Map[(String, String, Int), String]()
    val counts = scala.collection.mutable.Map[(String, String), Int]().withDefaultValue(0)
    sourceText.linesIterator
      .map(_.trim)
      .filter(line => line.nonEmpty && !isIgnoredLine(line))
      .foreach { line =>
        extractLabelFromLine(line).foreach { case (source, label, target) =>
          val key = (source, target)
          counts(key) += 1
          result((source, target, counts(key))) = label
        }
      }
    result.toMap

  private def extractLabelFromLine(line: String): Option[(String, String, String)] =
    PipeLabelPattern
      .findPrefixMatchOf(line)
      .map(m => (m.group(1), normalizeLabel(m.group(2)), m.group(3)))
      .filter { case (_, label, _) => label.nonEmpty }
      .orElse {
        InlineLabelPattern
          .findPrefixMatchOf(line)
          .map(m => (m.group(1), normalizeLabel(m.group(2)), m.group(3)))
          .filter { case (_, label, _) => label.nonEmpty }
      }

  private def isIgnoredLine(line: String): Boolean =
    val lower = line.toLowerCase
    IgnoredLinePrefixes.exists(prefix => lower.startsWith(prefix))

  private def normalizeLabel(raw: String): String =
    val trimmed = raw.trim
    val unquoted =
      if trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
      then trimmed.substring(1, trimmed.length - 1)
      else trimmed
    unquoted.replace("#quot;", "\"").trim
