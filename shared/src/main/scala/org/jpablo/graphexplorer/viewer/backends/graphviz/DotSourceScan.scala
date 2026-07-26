package org.jpablo.graphexplorer.viewer.backends.graphviz

/** Light source-text scanning for DOT documents, mirroring the mermaid package's
  * MermaidSourceScan. Deliberately regex-level: these helpers feed cosmetic features
  * (display titles) that must stay cheap enough to run per keystroke and per library
  * card, so they must not pay for a full DOT parse.
  */
private[backends] object DotSourceScan:

  // A bare graph-level `label = "..."` statement (graphviz renders it as the caption).
  private val BareLabel = """label\s*=\s*"([^"]*)"\s*;?""".r
  // The same attribute inside a single-line `graph [ ... ]` attribute statement.
  private val GraphAttrLabel = """label\s*=\s*"([^"]*)"""".r

  /** The graph's declared title: its top-level `label` attribute, when present. Scanning
    * stops at the first `subgraph`, so a cluster's label is never mistaken for the
    * graph's own. Only the top of the document is scanned — the graph label
    * conventionally sits in the header.
    */
  def graphTitle(text: String): Option[String] =
    text.linesIterator
      .map(_.trim)
      .take(50)
      .takeWhile(line => !line.startsWith("subgraph"))
      .flatMap(titleInLine)
      .nextOption()

  private def titleInLine(line: String): Option[String] =
    val candidate = line match
      case BareLabel(v)                => Some(v)
      case l if isGraphAttrStatement(l) => GraphAttrLabel.findFirstMatchIn(l).map(_.group(1))
      case _                            => None
    candidate.map(_.trim).filter(_.nonEmpty)

  // Whole-word `graph` only: a node that merely starts with it (`graphX [label=...]`)
  // must not donate its label as the graph title.
  private def isGraphAttrStatement(line: String): Boolean =
    line.startsWith("graph") &&
      (line.length == 5 || { val c = line.charAt(5); !c.isLetterOrDigit && c != '_' })
