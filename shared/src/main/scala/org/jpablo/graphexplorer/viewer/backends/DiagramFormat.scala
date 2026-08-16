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
    declared(text).getOrElse(DOT)

  /** The format the text POSITIVELY declares, or `None` when it declares nothing
    * either grammar recognizes.
    *
    * The distinction from [[detect]] is the DOT fallback. `detect` must always
    * answer, so an unrecognized document reads as DOT; callers that may only
    * OVERRULE an existing selection need evidence instead of a default — see
    * `ImportOps.restoreSource`, where guessing would silently undo a language
    * the user picked by hand.
    */
  def declared(text: String): Option[DiagramFormat] =
    val trimmed = text.trim.toLowerCase
    val isMermaid =
      mermaid.MermaidSourceScan.looksLikeMermaid(trimmed) || {
        val firstContent = trimmed.linesIterator
          .map(_.trim)
          .dropWhile(line => line.isEmpty || (line.startsWith("%%") && !line.startsWith("%%{")))
          .nextOption()
          .getOrElse("")
        mermaid.MermaidSourceScan.looksLikeMermaid(firstContent)
      }
    if isMermaid then Some(Mermaid)
    else Option.when(graphviz.DotSourceScan.diagramKind(text).isDefined)(DOT)
