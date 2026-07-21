package org.jpablo.graphexplorer.viewer.backends.mermaid

object MermaidVertexLabelFallback:
  import MermaidSourceScan.normalizeLabel

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
      .filterNot(line => MermaidSourceScan.isIgnoredLine(line))
      .foldLeft(Map.empty[String, String]) { (acc, line) =>
        val fromLine = extractVertexLabelsFromLine(line)
        acc ++ fromLine
      }

  /** Match only at the start of the line to avoid spurious matches inside
    * quoted node labels or edge label content (between `|...|`).
    *
    * For example, `STATE["Var[GraphState]\n{ text }"]` previously caused
    * the diamond pattern to match `n{ text }` mid-label, creating a phantom
    * vertex `n`. Anchoring to the line prefix prevents this.
    */
  private def extractVertexLabelsFromLine(line: String): Map[String, String] =
    // First matching pattern wins: NodeLabelPatterns are ordered most-specific first
    // (cylinder `[(..)]`, hexagon `{{..}}` before the general `[..]`/`{..}`), so a
    // general pattern can no longer override the specific shape nested inside it
    // (e.g. `A{{Hello}}` -> `Hello`, not `{Hello`).
    NodeLabelPatterns.iterator
      .flatMap(_.findPrefixMatchOf(line))
      .map(m => m.group(1) -> normalizeLabel(m.group(2)))
      .collectFirst { case kv @ (_, label) if label.nonEmpty => kv }
      .toMap
