package org.jpablo.graphexplorer.viewer.backends

/** Supported diagram input formats */
enum DiagramFormat derives CanEqual:
  case DOT
  case Mermaid

  def displayName: String = this match
    case DOT     => "DOT/Graphviz"
    case Mermaid => "Mermaid"

object DiagramFormat:

  /** Detect the diagram format from the input text.
    *
    * Detection rules:
    *   - Mermaid: starts with a known Mermaid diagram-kind prefix (the catalogue lives with
    *     the rest of the Mermaid grammar knowledge in [[mermaid.MermaidSourceScan]])
    *   - DOT: starts with digraph, graph (not followed by mermaid direction), strict digraph, strict graph
    *   - Default: DOT (backward compatibility)
    */
  def detect(text: String): DiagramFormat =
    val trimmed = text.trim.toLowerCase
    if mermaid.MermaidSourceScan.looksLikeMermaid(trimmed) then Mermaid
    else
      val firstContent = trimmed.linesIterator
        .map(_.trim)
        .dropWhile(line => line.isEmpty || (line.startsWith("%%") && !line.startsWith("%%{")))
        .nextOption()
        .getOrElse("")
      if mermaid.MermaidSourceScan.looksLikeMermaid(firstContent) then Mermaid
      else DOT
