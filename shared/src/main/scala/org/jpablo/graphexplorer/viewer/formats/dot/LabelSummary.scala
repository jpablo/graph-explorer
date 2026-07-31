package org.jpablo.graphexplorer.viewer.formats.dot

import org.jpablo.graphexplorer.graphviz.html.HtmlLabel
import org.jpablo.graphexplorer.graphviz.html.HtmlParser

/** Turning a label VALUE into text for lists, rows and search.
  *
  * A structured label is markup, and printing it raw makes every table node
  * read as `<table border="1" cellborder="0" cellsp…` — long, identical to its
  * neighbours, and mostly boilerplate. [[short]] gives the one line a row
  * wants; [[full]] gives every character the label renders, which is what
  * search must match against (searching the RAW form matches attribute names
  * like `cellborder` in every node, and misses nothing only by accident).
  */
object LabelSummary:

  val DefaultMaxLen = 40

  /** A cell/field carries the summary on its own once it has a letter and a
    * little length; below that (`1`, `#`, `✓` — a numbering or icon column)
    * the next one is pulled in. */
  private val MinMeaningful = 3
  private val MaxParts      = 3

  /** One line of markup-free text for a row: the label's "header" — the first
    * meaningful cell of a table, field of a record, or line of plain text —
    * collapsed to a single line and truncated.
    */
  def short(label: String, isRecord: Boolean = false, maxLen: Int = DefaultMaxLen): String =
    truncate(collapse(headline(label, isRecord)), maxLen)

  /** Every character of text the label renders, markup gone (for search). */
  def full(label: String, isRecord: Boolean = false): String =
    collapse(parts(label, isRecord).mkString(" "))

  private def headline(label: String, isRecord: Boolean): String =
    val ps = parts(label, isRecord).filter(_.trim.nonEmpty)
    if ps.isEmpty then ""
    else
      // Take the first part, then keep pulling while what we have is too weak
      // to identify the node. Length alone is the WRONG test: "Task 1" is a
      // perfect summary at 6 characters, and appending to it ("Task 1 1
      // Choose Menu") is strictly worse — so the trigger is having no letter
      // at all, or being shorter than a couple of characters.
      var taken = Vector(ps.head)
      while taken.length < MaxParts && ps.length > taken.length && isWeak(taken.mkString(" ")) do
        taken = taken :+ ps(taken.length)
      taken.mkString(" ")

  private def isWeak(s: String): Boolean =
    val t = s.trim
    t.length < MinMeaningful || !t.exists(_.isLetter)

  /** The label's text pieces in reading order: table cells, record fields, or
    * the lines of a plain/text label.
    */
  private def parts(label: String, isRecord: Boolean): Vector[String] =
    if HtmlLabels.isHtml(label) then
      HtmlParser.parse(label) match
        case Some(HtmlLabel.Table(tbl)) => HtmlLabelOps.cellTexts(tbl)
        case Some(other)                => Vector(HtmlLabelOps.plainText(other))
        // markup the engine will not render either — show it as-is rather than
        // inventing text for it
        case None => Vector(label)
    else if isRecord then RecordTree.leaves(RecordTree.parse(label)).map(l => RecordTree.displayText(l.text))
    else Vector(TextUtils.unescape(label))

  private def collapse(s: String): String =
    s.replaceAll("\\s+", " ").trim

  private def truncate(s: String, maxLen: Int): String =
    if s.length <= maxLen then s else s.take((maxLen - 1).max(1)).trim + "…"

end LabelSummary
