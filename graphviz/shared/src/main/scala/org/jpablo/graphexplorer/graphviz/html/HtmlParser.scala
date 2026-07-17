package org.jpablo.graphexplorer.graphviz.html

import scala.collection.mutable

/** Pure-Scala parser for Graphviz HTML-like label markup (the XML-ish grammar
  * `lib/common/htmlparse.y` + `htmllex.c`). gv drives it through expat, so it
  * is XML: case-insensitive tag names, standard entities, `<br/>` self-close,
  * and character data with control chars (`< 0x20`, i.e. newlines/tabs) removed
  * but spaces kept (`characterData`, htmllex.c:725).
  *
  * Scope: text runs with `<b>/<i>/<u>/<s>/<sub>/<sup>/<font>` styling, `<br/>`
  * breaks, and `<table>/<tr>/<td>`. Returns `None` on markup outside this subset
  * (caller falls back to treating it as a plain string).
  */
object HtmlParser:

  // ── tokenizer ──────────────────────────────────────────────────────────
  private enum Tok:
    case Open(name: String, attrs: Map[String, String], selfClose: Boolean)
    case Close(name: String)
    case Chars(str: String)

  private def decodeEntities(s: String): String =
    if s.indexOf('&') < 0 then s
    else
      val sb = new StringBuilder
      var i  = 0
      while i < s.length do
        val c = s.charAt(i)
        if c == '&' then
          val semi = s.indexOf(';', i)
          if semi > i then
            val ent = s.substring(i + 1, semi)
            val rep = ent match
              case "lt"   => "<"; case "gt"   => ">"; case "amp"  => "&"
              case "quot" => "\""; case "apos" => "'"; case "nbsp" => " "
              case e if e.startsWith("#x") || e.startsWith("#X") =>
                scala.util.Try(Integer.parseInt(e.drop(2), 16)).toOption.map(_.toChar.toString).getOrElse(s"&$e;")
              case e if e.startsWith("#") =>
                e.drop(1).toIntOption.map(_.toChar.toString).getOrElse(s"&$e;")
              // full HTML-4 named entity table (gv scanEntity / entities.h,
              // GENERATED into Entities.scala): &bull;/&rarr;/&nbsp;… decode
              // to their code points, then MEASURE as UTF-8 bytes (psg's
              // • = 3 space widths; nbsp U+00A0 = 2). Unknown names stay
              // literal, exactly like gv.
              case e => Entities.codepoints.get(e).map(Character.toString).getOrElse(s"&$ent;")
            sb.append(rep); i = semi + 1
          else { sb.append(c); i += 1 }
        else { sb.append(c); i += 1 }
      sb.toString

  /** Drop control chars (< space) but keep spaces/printables — characterData. */
  private def stripControl(s: String): String =
    if s.forall(_ >= ' ') then s else s.filter(_ >= ' ')

  private def tokenize(src: String): Option[List[Tok]] =
    val out = mutable.ListBuffer.empty[Tok]
    var i   = 0
    val n   = src.length
    while i < n do
      val c = src.charAt(i)
      if c == '<' then
        if src.startsWith("<!--", i) then
          val end = src.indexOf("-->", i)
          if end < 0 then return None else i = end + 3
        else
          val gt = src.indexOf('>', i)
          if gt < 0 then return None
          val raw = src.substring(i + 1, gt).trim
          if raw.isEmpty then return None
          if raw.startsWith("/") then
            out += Tok.Close(raw.drop(1).trim.toLowerCase)
          else
            val selfClose = raw.endsWith("/")
            val body      = (if selfClose then raw.dropRight(1) else raw).trim
            parseTag(body) match
              case Some((name, attrs)) => out += Tok.Open(name, attrs, selfClose)
              case None                => return None
          i = gt + 1
      else
        val lt = src.indexOf('<', i)
        val end = if lt < 0 then n else lt
        val txt = stripControl(decodeEntities(src.substring(i, end)))
        if txt.nonEmpty then out += Tok.Chars(txt)
        i = end
    Some(out.toList)

  /** Split `name attr="v" attr2='v2'` into (lower name, attr map, lower keys). */
  private def parseTag(body: String): Option[(String, Map[String, String])] =
    val sp = body.indexWhere(_.isWhitespace)
    if sp < 0 then Some((body.toLowerCase, Map.empty))
    else
      val name  = body.substring(0, sp).toLowerCase
      val rest  = body.substring(sp + 1)
      val attrs = mutable.LinkedHashMap.empty[String, String]
      var i     = 0
      val n     = rest.length
      while i < n do
        while i < n && rest.charAt(i).isWhitespace do i += 1
        val eq = rest.indexOf('=', i)
        if eq < 0 then i = n
        else
          val key = rest.substring(i, eq).trim.toLowerCase
          var j   = eq + 1
          while j < n && rest.charAt(j).isWhitespace do j += 1
          if j < n && (rest.charAt(j) == '"' || rest.charAt(j) == '\'') then
            val q   = rest.charAt(j)
            val end = rest.indexOf(q, j + 1)
            if end < 0 then i = n
            else { attrs(key) = decodeEntities(rest.substring(j + 1, end)); i = end + 1 }
          else
            var k = j
            while k < n && !rest.charAt(k).isWhitespace do k += 1
            attrs(key) = decodeEntities(rest.substring(j, k)); i = k
      Some((name, attrs.toMap))

  // ── parser ─────────────────────────────────────────────────────────────
  private val styleTags = Set("b", "i", "u", "s", "sub", "sup", "o", "font")

  def parse(markup: String): Option[HtmlLabel] =
    tokenize(markup).flatMap { toks =>
      // A label is a single top-level table, a bare image, or a text block.
      val firstOpen = toks.collectFirst { case Tok.Open(n, _, _) => n }
      firstOpen match
        case Some("table") => parseTable(toks).map(HtmlLabel.Table.apply)
        case Some("img")   => imgOf(toks)
        case _             => parseText(toks).map(HtmlLabel.Text.apply)
    }

  /** The first `<img>` token as an [[HtmlLabel.Image]] (src + optional scale). */
  private def imgOf(toks: List[Tok]): Option[HtmlLabel] =
    toks.collectFirst { case Tok.Open("img", a, _) =>
      HtmlLabel.Image(a.getOrElse("src", ""), a.get("scale"))
    }

  private def applyFont(f: HtmlFont, name: String, a: Map[String, String]): HtmlFont =
    name match
      case "b"    => f.copy(bold = true)
      case "i"    => f.copy(italic = true)
      case "u"    => f.copy(underline = true)
      case "s"    => f.copy(strike = true)
      case "sub"  => f.copy(sub = true)
      case "sup"  => f.copy(sup = true)
      case "font" =>
        f.copy(
          size  = a.get("point-size").flatMap(_.toDoubleOption).orElse(f.size),
          name  = a.get("face").orElse(f.name),
          color = a.get("color").orElse(f.color)
        )
      case _ => f

  /** Parse a text block: runs + `<br/>` line breaks, styling from a font stack. */
  private def parseText(toks: List[Tok]): Option[HtmlText] =
    val spans   = mutable.ListBuffer.empty[HtmlSpan]
    val cur     = mutable.ListBuffer.empty[HtmlItem]
    val stack   = mutable.Stack(HtmlFont())
    // Each `<br>` may set the *just-ended* line's alignment; `None` inherits
    // the enclosing default (resolved at render — cell align or centre).
    def flush(align: Option[HtmlAlign]): Unit =
      spans += HtmlSpan(cur.toList, align); cur.clear()
    var ok = true
    val it = toks.iterator
    while it.hasNext && ok do
      it.next() match
        case Tok.Chars(s)               => cur += HtmlItem(s, stack.top)
        case Tok.Open("br", a, _)       => flush(alignOf(a))
        case Tok.Open(n, a, selfClose) if styleTags(n) =>
          if selfClose then () else stack.push(applyFont(stack.top, n, a))
        case Tok.Close(n) if styleTags(n) => if stack.size > 1 then stack.pop()
        // <img> contributes no text; its cell is sized by FIXEDSIZE/WIDTH/HEIGHT
        // (reading the file for the natural size is out of scope). Tolerate the
        // implicit </img> close too.
        case Tok.Open("img", _, _)      => ()
        case Tok.Close("img")           => ()
        case _                          => ok = false
    if !ok then None
    else
      if cur.nonEmpty || spans.isEmpty then flush(None)
      Some(HtmlText(spans.toList))

  private def alignOf(a: Map[String, String]): Option[HtmlAlign] =
    a.get("align").map(_.toLowerCase).collect {
      case "left"   => HtmlAlign.Left
      case "right"  => HtmlAlign.Right
      case "center" => HtmlAlign.Center
      case "text"   => HtmlAlign.Text_
    }

  // ── table parser ───────────────────────────────────────────────────────
  private def parseTable(toks: List[Tok]): Option[HtmlTable] =
    val arr = toks.toArray
    var i   = 0
    // skip to <table>
    while i < arr.length && !arr(i).isInstanceOf[Tok.Open] do i += 1
    arr.lift(i) match
      case Some(Tok.Open("table", tattrs, _)) =>
        i += 1
        val rows = mutable.ListBuffer.empty[List[HtmlCell]]
        val hrs  = mutable.Set.empty[Int] // boundary index (row below) with an <hr/>
        val vrs  = mutable.Set.empty[Int] // column boundary (col to the right) with a <vr/>
        var ok   = true
        while i < arr.length && ok do
          arr(i) match
            case Tok.Open("tr", _, _) =>
              i += 1
              val cells = mutable.ListBuffer.empty[HtmlCell]
              while i < arr.length && !isClose(arr(i), "tr") && ok do
                arr(i) match
                  case Tok.Open("td", cattrs, _) =>
                    val (cell, next) = parseCell(arr, i + 1, cattrs)
                    cell match
                      case Some(c) => cells += c; i = next
                      case None    => ok = false
                  case Tok.Open("vr", _, _) => vrs += cells.size; i += 1
                  case Tok.Close("tr")      => // handled by while guard
                  case _                    => i += 1 // tolerate stray whitespace tokens
              if i < arr.length && isClose(arr(i), "tr") then i += 1
              rows += cells.toList
            case Tok.Open("hr", _, _) => hrs += rows.size; i += 1
            case Tok.Close("table")   => i = arr.length
            case _                    => i += 1
        if !ok then None
        else Some(mkTable(rows.toList, tattrs, hrs.toSet, vrs.toSet))
      case _ => None

  private def isClose(t: Tok, name: String): Boolean = t match
    case Tok.Close(n) => n == name
    case _            => false

  /** Parse a `<td>`…`</td>` body (text or a nested table) starting at `start`. */
  private def parseCell(arr: Array[Tok], start: Int, attrs: Map[String, String]): (Option[HtmlCell], Int) =
    var depth = 1
    var i     = start
    val body  = mutable.ListBuffer.empty[Tok]
    while i < arr.length && depth > 0 do
      arr(i) match
        case Tok.Open("td", _, false) => depth += 1; body += arr(i); i += 1
        case Tok.Close("td")          => depth -= 1; if depth > 0 then body += arr(i); i += 1
        case t                        => body += t; i += 1
    // A cell holds a nested table, a bare image (`<td>` whose only content is
    // an `<img>`), or a text block. An img mixed with real text keeps the text
    // path (parseText tolerates the img) — the img-only case is the common one.
    val hasTable = body.exists { case Tok.Open("table", _, _) => true; case _ => false }
    val hasText  = body.exists { case Tok.Chars(s) => s.trim.nonEmpty; case _ => false }
    val imgTok   = body.collectFirst { case Tok.Open("img", _, _) => () }
    val content =
      if hasTable then parseTable(body.toList).map(HtmlLabel.Table.apply)
      else if imgTok.isDefined && !hasText then imgOf(body.toList)
      else parseText(body.toList).map(HtmlLabel.Text.apply)
    (content.map(c => HtmlCell(c, attrs)), i)

  private def mkTable(rows: List[List[HtmlCell]], a: Map[String, String],
                      hrAfter: Set[Int], vrAfter: Set[Int]): HtmlTable =
    def int(k: String, d: Int) = a.get(k).flatMap(_.toIntOption).getOrElse(d)
    HtmlTable(
      rows        = rows,
      border      = int("border", HtmlTable.DefaultBorder),
      cellborder  = a.get("cellborder").flatMap(_.toIntOption),
      cellspacing = int("cellspacing", HtmlTable.DefaultCellSpacing),
      cellpadding = int("cellpadding", HtmlTable.DefaultCellPadding),
      align       = alignOf(a).getOrElse(HtmlAlign.Center),
      attrs       = a,
      hrAfter     = hrAfter,
      vrAfter     = vrAfter
    )

end HtmlParser
