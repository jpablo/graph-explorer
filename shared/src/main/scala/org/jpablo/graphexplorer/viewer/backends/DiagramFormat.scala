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
    * Detection rules, in order:
    *   - Mermaid: a known diagram-kind header, or a leading `%%{…}%%` directive
    *     (the catalogue lives with the rest of the Mermaid grammar knowledge in
    *     [[mermaid.MermaidSourceScan]])
    *   - DOT: a `digraph`/`graph` declaration, optionally `strict`
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
    // Each scanner is handed the RAW text and skips whatever its own grammar
    // says to skip — frontmatter and `%%` comments for Mermaid, comment lines
    // ahead of the declaration for DOT. This used to pre-lowercase and hand
    // Mermaid a first-content-line it had guessed at, which meant the skipping
    // rules lived half here and half in the scanner.
    if mermaid.MermaidSourceScan.looksLikeMermaid(text) then Some(Mermaid)
    else Option.when(graphviz.DotSourceScan.diagramKind(text).isDefined)(DOT)
