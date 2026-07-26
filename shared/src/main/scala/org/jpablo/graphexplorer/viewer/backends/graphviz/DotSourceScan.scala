package org.jpablo.graphexplorer.viewer.backends.graphviz

/** Light source-text scanning for DOT documents, mirroring the mermaid package's
  * MermaidSourceScan. Deliberately regex-level: these helpers feed cosmetic features
  * (display titles) that must stay cheap enough to run per keystroke and per library
  * card, so they must not pay for a full DOT parse.
  */
private[backends] object DotSourceScan:

  // A `label = "..."` statement standing alone on its line (trailing comma allowed:
  // inside a multi-line attribute list the entries are comma-separated).
  private val BareLabel = """label\s*=\s*"([^"]*)"\s*,?\s*;?""".r
  // The same attribute anywhere inside a single-line `graph [ ... ]` statement.
  private val GraphAttrLabel = """label\s*=\s*"([^"]*)"""".r
  // Quoted spans, dropped before counting brackets so a bracket inside a label
  // (`label="a[b"`) cannot skew the nesting depth.
  private val QuotedSpan = """"[^"]*"""".r

  /** The graph's declared title: its top-level `label` attribute, when present.
    *
    * Only the top of the document is scanned — the graph label conventionally sits in
    * the header — and scanning stops at the first `subgraph`, so a cluster's label is
    * never mistaken for the graph's own.
    */
  def graphTitle(text: String): Option[String] =
    text.linesIterator
      .map(_.trim)
      .take(50)
      .takeWhile(line => !line.startsWith("subgraph"))
      .foldLeft(Scan.start)(_.next(_))
      .title

  /** Nesting state carried down the document.
    *
    * Depth is what separates a graph title from a node's label: both are written
    * `label="..."`, and in the multi-line attribute form
    * {{{
    * "LR_0" [
    *     label="LR_0"
    * ];
    * }}}
    * the node's label stands alone on its line, indistinguishable from a top-level
    * statement unless we remember that an attribute list is open and who opened it.
    */
  private case class Scan(depth: Int, inGraphAttrs: Boolean, title: Option[String]):

    def next(line: String): Scan =
      val opensGraphAttrs = depth == 0 && isGraphAttrStatement(line)
      val candidate =
        if depth == 0 then
          line match
            case BareLabel(v)         => Some(v)
            case _ if opensGraphAttrs => GraphAttrLabel.findFirstMatchIn(line).map(_.group(1))
            case _                    => None
        else if inGraphAttrs then
          line match
            case BareLabel(v) => Some(v)
            case _            => None
        else None

      val newDepth = 0 max (depth + bracketBalance(line))
      Scan(
        depth = newDepth,
        inGraphAttrs = newDepth > 0 && (inGraphAttrs || opensGraphAttrs),
        title = title.orElse(candidate.map(_.trim).filter(_.nonEmpty))
      )

  private object Scan:
    val start = Scan(depth = 0, inGraphAttrs = false, title = None)

  private def bracketBalance(line: String): Int =
    val unquoted = QuotedSpan.replaceAllIn(line, "")
    unquoted.count(_ == '[') - unquoted.count(_ == ']')

  // Whole-word `graph` only: a node that merely starts with it (`graphX [label=...]`)
  // must not donate its label as the graph title.
  private def isGraphAttrStatement(line: String): Boolean =
    line.startsWith("graph") &&
      (line.length == 5 || { val c = line.charAt(5); !c.isLetterOrDigit && c != '_' })
