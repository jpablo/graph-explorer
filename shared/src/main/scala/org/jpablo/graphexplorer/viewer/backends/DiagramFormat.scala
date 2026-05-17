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
    *   - Mermaid: starts with flowchart, graph (followed by direction), sequenceDiagram, classDiagram, etc.
    *   - DOT: starts with digraph, graph (not followed by mermaid direction), strict digraph, strict graph
    *   - Default: DOT (backward compatibility)
    */
  def detect(text: String): DiagramFormat =
    val trimmed = text.trim.toLowerCase
    if isMermaid(trimmed) then Mermaid
    else
      val firstContent = trimmed.linesIterator
        .map(_.trim)
        .dropWhile(line => line.isEmpty || (line.startsWith("%%") && !line.startsWith("%%{")))
        .nextOption()
        .getOrElse("")
      if isMermaid(firstContent) then Mermaid
      else DOT

  private def isMermaid(text: String): Boolean =
    // Mermaid flowchart patterns
    text.startsWith("flowchart") ||
    text.startsWith("graph td") ||
    text.startsWith("graph tb") ||
    text.startsWith("graph bt") ||
    text.startsWith("graph lr") ||
    text.startsWith("graph rl") ||
    // Other Mermaid diagram types
    text.startsWith("sequencediagram") ||
    text.startsWith("classdiagram") ||
    text.startsWith("statediagram") ||
    text.startsWith("erdiagram") ||
    text.startsWith("journey") ||
    text.startsWith("gantt") ||
    text.startsWith("pie") ||
    text.startsWith("mindmap") ||
    text.startsWith("timeline") ||
    text.startsWith("gitgraph") ||
    // Mermaid directive marker
    text.startsWith("%%{") ||
    text.startsWith("---") // YAML frontmatter often used in Mermaid
