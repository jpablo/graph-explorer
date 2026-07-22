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

  // Lowercased prefixes of every Mermaid diagram type bundled with mermaid 11
  // (plus the flowchart `graph <dir>` forms, directives and YAML frontmatter).
  private val mermaidPrefixes = List(
    "flowchart",
    "graph td",
    "graph tb",
    "graph bt",
    "graph lr",
    "graph rl",
    "sequencediagram",
    "classdiagram",
    "statediagram",
    "erdiagram",
    "journey",
    "gantt",
    "pie",
    "mindmap",
    "timeline",
    "gitgraph",
    "quadrantchart",
    "xychart",
    "sankey",
    "requirementdiagram",
    "c4context",
    "c4container",
    "c4component",
    "c4dynamic",
    "c4deployment",
    "block-beta",
    "kanban",
    "packet",
    "radar",
    "architecture",
    "treemap",
    "zenuml",
    // Mermaid directive marker
    "%%{",
    "---" // YAML frontmatter often used in Mermaid
  )

  private def isMermaid(text: String): Boolean =
    mermaidPrefixes.exists(text.startsWith)
