package org.jpablo.graphexplorer.viewer.components.labelEditor

import scala.collection.mutable.ListBuffer

/** The places of the cells in the markup of a table label.
  *
  * The formatted editor writes again only the cells that changed. To do that, it must know where the content of each
  * cell is in the markup. `HtmlParser` gives the cells, but not their places. This scanner reads the tags as
  * `HtmlParser` does: a tag ends at the first `>`, the names have no case, and a comment `<!-- -->` is not a tag. A
  * `<TD>` in the content of a cell (in a nested table) is part of that cell.
  */
object TableMarkup:

  /** One tag of the markup. `end` is the index after its `>`. */
  private final case class Tag(name: String, close: Boolean, selfClose: Boolean, start: Int, end: Int)

  /** The tags of `src`, or None when a tag has no end. */
  private def tags(src: String): Option[List[Tag]] =
    val out = ListBuffer.empty[Tag]
    var i   = 0
    val n   = src.length
    var ok  = true
    while ok && i < n do
      if src.startsWith("<!--", i) then
        val end = src.indexOf("-->", i)
        if end < 0 then ok = false else i = end + 3
      else if src.charAt(i) == '<' then
        val gt = src.indexOf('>', i)
        if gt < 0 then ok = false
        else
          val raw   = src.substring(i + 1, gt).trim
          val close = raw.startsWith("/")
          val body  = (if close then raw.drop(1) else raw).trim
          val name  = body.takeWhile(c => !c.isWhitespace && c != '/').toLowerCase
          out += Tag(name, close, !close && body.endsWith("/"), i, gt + 1)
          i = gt + 1
      else
        val lt = src.indexOf('<', i)
        i = if lt < 0 then n else lt
    Option.when(ok)(out.toList)

  /** The content of each cell of the first table of `markup`: for each row, the start and the end of the content of
    * each cell. None when `markup` has no complete table, or when a cell is a `<TD/>` with no content.
    */
  def cellRanges(markup: String): Option[List[List[(Int, Int)]]] =
    tags(markup).flatMap: all =>
      val rows      = ListBuffer.empty[ListBuffer[(Int, Int)]]
      var depth     = 0 // the depth of the tables, out of the cells
      var tdDepth   = 0 // the depth of the cells, in a cell of the first table
      var cellStart = 0
      var done      = false
      var ok        = true
      all.iterator.takeWhile(_ => !done && ok).foreach: t =>
        if tdDepth > 0 then
          if t.name == "td" && !t.close && !t.selfClose then tdDepth += 1
          else if t.name == "td" && t.close then
            tdDepth -= 1
            if tdDepth == 0 then rows.last += ((cellStart, t.start))
        else
          t.name match
            case "table" if !t.close => depth += 1
            case "table" =>
              depth -= 1
              if depth == 0 then done = true
            case "tr" if !t.close && depth == 1 => rows += ListBuffer.empty
            case "td" if !t.close && depth == 1 =>
              if t.selfClose || rows.isEmpty then ok = false
              else
                cellStart = t.end
                tdDepth = 1
            case _ => ()
      Option.when(ok && done && rows.nonEmpty)(rows.map(_.toList).toList)

end TableMarkup
