package org.jpablo.graphexplorer.graphviz.html

/** Writes an [[HtmlText]] as Graphviz HTML-like markup.
  *
  * The writer gives one canonical form. [[HtmlParser.parse]] reads this form
  * back to the same text, with adjacent runs of the same font joined and empty
  * runs removed.
  *
  *   - Each run gets one `<FONT>` with only the attributes that the run sets:
  *     FACE, POINT-SIZE, COLOR, in this order. A run with no attributes gets
  *     no `<FONT>`.
  *   - The style tags go inside the `<FONT>` in this order: `<B>`, `<I>`,
  *     `<U>`, `<O>`, `<S>`, `<SUB>`, `<SUP>`.
  *   - A `<BR/>` ends each line except the last line. The `<BR/>` has the
  *     alignment of the line that it ends.
  *   - The last line gets a `<BR/>` when it has an alignment. An empty last
  *     line after other lines also gets a `<BR/>`, because the parser makes a
  *     line only from a `<BR/>` or from text.
  */
object HtmlWriter:

  def write(text: HtmlText): String =
    val sb    = StringBuilder()
    val count = text.spans.length
    text.spans.zipWithIndex.foreach: (span, i) =>
      val runs = joinRuns(span.items)
      runs.foreach(run => writeRun(sb, run))
      val isLast   = i == count - 1
      val needsBr  = !isLast || span.align.isDefined || (runs.isEmpty && count > 1)
      if needsBr then
        sb ++= "<BR"
        span.align.foreach(a => sb ++= s""" ALIGN="${alignName(a)}"""")
        sb ++= "/>"
    sb.toString

  /** The runs of a line, with empty runs removed and adjacent runs of the
    * same font joined. The parser does the same when it reads the markup. */
  private def joinRuns(items: List[HtmlItem]): List[HtmlItem] =
    items
      .filter(_.str.nonEmpty)
      .foldLeft(List.empty[HtmlItem]):
        case (prev :: rest, item) if prev.font == item.font => prev.copy(str = prev.str + item.str) :: rest
        case (acc, item)                                     => item :: acc
      .reverse

  private def writeRun(sb: StringBuilder, run: HtmlItem): Unit =
    val f = run.font
    val attrs =
      f.name.map(n => s""" FACE="${escapeAttr(n)}"""").getOrElse("") +
        f.size.map(s => s""" POINT-SIZE="${number(s)}"""").getOrElse("") +
        f.color.map(c => s""" COLOR="${escapeAttr(c)}"""").getOrElse("")
    // The style tags, from the outer tag to the inner tag.
    val tags = List(
      f.bold      -> "B",
      f.italic    -> "I",
      f.underline -> "U",
      f.overline  -> "O",
      f.strike    -> "S",
      f.sub       -> "SUB",
      f.sup       -> "SUP"
    ).collect { case (true, tag) => tag }
    if attrs.nonEmpty then sb ++= s"<FONT$attrs>"
    tags.foreach(tag => sb ++= s"<$tag>")
    sb ++= escapeText(run.str)
    tags.reverseIterator.foreach(tag => sb ++= s"</$tag>")
    if attrs.nonEmpty then sb ++= "</FONT>"

  private def alignName(a: HtmlAlign): String = a match
    case HtmlAlign.Left   => "LEFT"
    case HtmlAlign.Center => "CENTER"
    case HtmlAlign.Right  => "RIGHT"
    case HtmlAlign.Text_  => "TEXT"

  /** A whole number has no ".0". Other numbers keep their decimals. */
  private def number(d: Double): String =
    if d.isWhole && math.abs(d) < 1e15 then d.toLong.toString else d.toString

  private def escapeText(s: String): String =
    s.flatMap:
      case '&' => "&amp;"
      case '<' => "&lt;"
      case '>' => "&gt;"
      case c   => c.toString

  /** The tokenizer ends a tag at the first `>`, also in a quoted value. Thus
    * the writer escapes `>` in an attribute value too. */
  private def escapeAttr(s: String): String =
    s.flatMap:
      case '&' => "&amp;"
      case '<' => "&lt;"
      case '>' => "&gt;"
      case '"' => "&quot;"
      case c   => c.toString

end HtmlWriter
