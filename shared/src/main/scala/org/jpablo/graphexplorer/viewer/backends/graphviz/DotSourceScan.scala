package org.jpablo.graphexplorer.viewer.backends.graphviz

/** Light source-text scanning for DOT documents, mirroring the mermaid package's
  * MermaidSourceScan. Deliberately regex-level: these helpers feed cosmetic features
  * (display titles) that must stay cheap enough to run per keystroke and per library
  * card, so they must not pay for a full DOT parse.
  */
private[backends] object DotSourceScan:

  // The text between the quotes of a DOT string. A backslash escapes the next
  // character, thus `\"` does not end the string, but the quote in `"C:\\"` does.
  // The escaped character can be a line break: DOT continues the string on the
  // next line. This is the one form of this rule (see [[quotedStringEnd]]).
  private val QuotedText = """[^"\\]*(?:\\[\s\S][^"\\]*)*"""
  // A `label = "..."` statement standing alone on its line (trailing comma allowed:
  // inside a multi-line attribute list the entries are comma-separated).
  private val BareLabel = raw"""label\s*=\s*"($QuotedText)"\s*,?\s*;?""".r
  // The same attribute anywhere inside a single-line `graph [ ... ]` statement.
  private val GraphAttrLabel = raw"""label\s*=\s*"($QuotedText)"""".r
  // Quoted spans, dropped before counting brackets so a bracket inside a label
  // (`label="a[b"`) cannot skew the nesting depth.
  private val QuotedSpan = raw""""$QuotedText"""".r
  // The text of a DOT string after its opening quote, with the closing quote.
  private val QuotedTextAndClose = raw"""$QuotedText"""".r

  // The graph declaration keyword, optionally preceded by `strict`. Whole-word and
  // case-independent, like DOT's own keywords — so a line starting `graph_config`
  // (or, inside the body, nothing: the declaration always comes first) cannot match.
  private val GraphDecl = """(?i)^(?:strict\s+)?(digraph|graph)\b.*""".r

  /** `digraph` vs `graph`: DOT's own vocabulary for its two diagram kinds (directed
    * vs undirected edges), read from the graph declaration. Comment lines ahead of
    * the declaration are skipped by anchoring, not by parsing them.
    */
  def diagramKind(text: String): Option[String] =
    text.linesIterator
      .map(_.trim)
      .take(50)
      .collectFirst { case GraphDecl(kw) => kw.toLowerCase }

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
        title = title.orElse(candidate.map(v => unescapeQuotes(v).trim).filter(_.nonEmpty))
      )

  private object Scan:
    val start = Scan(depth = 0, inGraphAttrs = false, title = None)

  /** The text of a quoted string as the DOT reader gives it: `\"` becomes `"`, and
    * each other escape stays as it is. Graphviz reads a label in the same way.
    *
    * In a [[QuotedText]] capture, each backslash escapes the next character. Thus
    * the backslash before a quote always escapes that quote, and a plain replace
    * finds the same escapes as a left-to-right scan.
    */
  private def unescapeQuotes(quoted: String): String =
    quoted.replace("\\\"", "\"")

  /** The index after the quote that closes the DOT string that opens just before
    * `from`, or `None` when the string does not close. A backslash escapes the next
    * character, as in [[QuotedText]].
    */
  def quotedStringEnd(text: String, from: Int): Option[Int] =
    QuotedTextAndClose.findPrefixMatchOf(text.substring(from)).map(from + _.end)

  private def bracketBalance(line: String): Int =
    val unquoted = QuotedSpan.replaceAllIn(line, "")
    unquoted.count(_ == '[') - unquoted.count(_ == ']')

  // Whole-word `graph` only: a node that merely starts with it (`graphX [label=...]`)
  // must not donate its label as the graph title.
  private def isGraphAttrStatement(line: String): Boolean =
    line.startsWith("graph") &&
      (line.length == 5 || { val c = line.charAt(5); !c.isLetterOrDigit && c != '_' })
