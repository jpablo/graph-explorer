package org.jpablo.graphexplorer.viewer.backends.mermaid

object MermaidVertexLabelFallback:
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

  private val NodeLabelPatterns = List(
    raw"""([A-Za-z0-9_][A-Za-z0-9_-]*)\s*\[\(([^\]]+?)\)\]""".r,      // cylinder: A[(DB)]
    raw"""([A-Za-z0-9_][A-Za-z0-9_-]*)\s*\(\[([^\]]+?)\]\)""".r,      // stadium: A([Text])
    raw"""([A-Za-z0-9_][A-Za-z0-9_-]*)\s*\(\(([^\)]+?)\)\)""".r,      // circle: A((Text))
    raw"""([A-Za-z0-9_][A-Za-z0-9_-]*)\s*\{\{([^\}]+?)\}\}""".r,      // hexagon: A{{Text}}
    raw"""([A-Za-z0-9_][A-Za-z0-9_-]*)\s*\{([^\}]+?)\}""".r,            // diamond: A{Text}
    raw"""([A-Za-z0-9_][A-Za-z0-9_-]*)\s*\[([^\]]+?)\]""".r             // rectangle: A[Text]
  )

  /** Mermaid parser output can occasionally drop vertex labels and return text equal to node id.
    * Recover missing labels from raw source text while keeping parser-provided labels authoritative.
    */
  def withSourceVertexLabels(
      sourceText: String,
      vertices:   Map[String, MermaidVertex]
  ): Map[String, MermaidVertex] =
    val labels = extractVertexLabelsFromText(sourceText)
    vertices.map { case (rawKey, vertex) =>
      val candidateIds = Vector(rawKey, vertex.id).map(_.trim).filter(_.nonEmpty).distinct
      val parserMissingLabel = vertex.text.trim.isEmpty || candidateIds.contains(vertex.text)
      val sourceLabel = candidateIds.iterator
        .flatMap(id => labels.get(id))
        .find(_.nonEmpty)
      if parserMissingLabel then
        sourceLabel
          .filter(label => !candidateIds.contains(label))
          .map(label => rawKey -> vertex.copy(text = label))
          .getOrElse(rawKey -> vertex)
      else rawKey -> vertex
    }

  /** Parses node labels from Mermaid source text.
    *
    * Supports common flowchart node syntaxes like `A[Label]`, `A((Label))`, `A{Label}` and variants.
    */
  def extractVertexLabelsFromText(sourceText: String): Map[String, String] =
    sourceText.linesIterator
      .map(_.trim)
      .filter(line => line.nonEmpty)
      .filterNot(line => shouldIgnoreLine(line))
      .foldLeft(Map.empty[String, String]) { (acc, line) =>
        val fromLine = extractVertexLabelsFromLine(line)
        acc ++ fromLine
      }

  private def shouldIgnoreLine(line: String): Boolean =
    val lower = line.toLowerCase
    IgnoredLinePrefixes.exists(prefix => lower.startsWith(prefix))

  private def extractVertexLabelsFromLine(line: String): Map[String, String] =
    NodeLabelPatterns.foldLeft(Map.empty[String, String]) { (acc, pattern) =>
      pattern.findAllMatchIn(line).foldLeft(acc) { (innerAcc, m) =>
        val nodeId = m.group(1)
        val raw    = m.group(2)
        val label  = normalizeLabel(raw)
        if label.nonEmpty then innerAcc + (nodeId -> label)
        else innerAcc
      }
    }

  private def normalizeLabel(raw: String): String =
    val trimmed = raw.trim
    val unquoted =
      if trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
      then trimmed.substring(1, trimmed.length - 1)
      else trimmed

    unquoted.replace("#quot;", "\"").trim
