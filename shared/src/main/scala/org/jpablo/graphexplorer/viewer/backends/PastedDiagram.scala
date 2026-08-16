package org.jpablo.graphexplorer.viewer.backends

/** Diagram source lifted out of whatever the clipboard actually held.
  *
  * "Whatever" is the point. Text copied from a chat answer, a README or an issue
  * arrives wrapped in a Markdown code fence, often with prose around it — and
  * that fence's info string (` ```mermaid `) is a stronger statement of the
  * language than anything [[DiagramFormat.detect]] can infer from the body,
  * which sees only backticks and gives up on DOT.
  */
final case class PastedDiagram(source: String, format: DiagramFormat) derives CanEqual

object PastedDiagram:

  /** Info strings that name a language we speak. Any other fence (` ```js `, or
    * none at all) is still stripped — only the format claim is ignored, and
    * detection has the last word.
    */
  private val FormatByInfoString: Map[String, DiagramFormat] = Map(
    "mermaid"  -> DiagramFormat.Mermaid,
    "mmd"      -> DiagramFormat.Mermaid,
    "dot"      -> DiagramFormat.DOT,
    "gv"       -> DiagramFormat.DOT,
    "graphviz" -> DiagramFormat.DOT
  )

  /** A whole line that is nothing but a fence marker and (optionally) its info
    * string. Anchored end-to-end on purpose: a backtick inside a DOT label is
    * not the start of a code block.
    */
  private val FenceLine = """^\s*(`{3,}|~{3,})\s*([A-Za-z0-9_+#.-]*)\s*$""".r

  /** The pasted text as a diagram, or `None` when there is nothing to paste.
    *
    * `None` — rather than an empty document — is what keeps an empty clipboard
    * from silently wiping the diagram the user is looking at.
    */
  def from(raw: String): Option[PastedDiagram] =
    // Normalized eagerly: a Windows-origin paste otherwise carries CR into every
    // label, where nothing downstream trims it back out.
    val text         = raw.replace("\r\n", "\n").replace("\r", "\n")
    val (body, hint) = unfence(text)
    Option.when(body.trim.nonEmpty):
      PastedDiagram(body, hint.getOrElse(DiagramFormat.detect(body)))

  /** The first fenced block's contents and declared language, or the text
    * verbatim when it carries no fence.
    */
  private def unfence(text: String): (String, Option[DiagramFormat]) =
    val lines = text.linesIterator.toVector
    val opening = lines.iterator.zipWithIndex.collectFirst:
      case (FenceLine(marker, info), i) => (i, marker, info)
    opening match
      case None => (text, None)
      case Some((start, marker, info)) =>
        // A closing fence repeats the opening character at least as many times,
        // and carries no info string of its own (CommonMark).
        def isClosing(line: String): Boolean = line match
          case FenceLine(m, "") => m.head == marker.head && m.length >= marker.length
          case _                => false
        // An unterminated fence still means "everything after it is the code":
        // a paste truncated at the end is better opened than rejected.
        val end       = lines.indexWhere(isClosing, start + 1)
        val bodyLines = if end >= 0 then lines.slice(start + 1, end) else lines.drop(start + 1)
        (bodyLines.mkString("\n"), FormatByInfoString.get(info.toLowerCase))
