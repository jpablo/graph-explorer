package org.jpablo.graphexplorer.graphviz.output

import org.jpablo.graphexplorer.graphviz.model.RGraph
import org.jpablo.graphexplorer.graphviz.layout.{Arrow, Coord, GraphBB, NodeSize, Rank, Spline, XCoord}

/** Phase 5 of the `dot` pipeline: the `svg` output writer (M7 increment 2).
  *
  * Faithful reimplementation of Graphviz 13.0.1's SVG renderer
  * (`plugin/core/gvrender_core_svg.c` + `lib/common/emit.c`/`labels.c`),
  * scoped to label-free TB poly (ellipse) nodes:
  *
  *  - header / `<svg>` / `viewBox` / `translate` (flipped-y) / background —
  *    bit-exact (derived from the goldens, cross-checked vs the C);
  *  - per node: `<ellipse>` (cx,−cy,rx=lw,ry=ht/2) + centered `<text>` whose
  *    baseline y = `−(cy + dimenY/2 − fontsize + 0.1·fontsize)` with
  *    `dimenY = fontsize·LINESPACING` (labels.c `emit_label` + textspan.c
  *    `yoffset_centerline`) — reproduces the golden exactly, not fitted;
  *  - per edge: `<path d="M..C..">` from the installed spline (y-negated) +,
  *    for a head arrow, the normal-arrowhead `<polygon>` (a[1..3] of
  *    `arrow_type_normal0`). The arrowhead's `delta_tip`/`delta_base` miter
  *    refinement is the same documented sub-2px M5 deferral.
  *
  * Numbers use Graphviz's `gvprintdouble` (`%.2f`, trailing zeros trimmed,
  * |x|<0.005 → "0"). Gated structurally + visually-close (ε) vs golden svg.
  */
object Svg:

  private val Margin       = 4.0   // GAP / default graph margin (pt)
  private val FontSize     = 14.0  // DEFAULT_FONTSIZE

  /** `ps_font_equiv.h` PostScript aliases, NATIVEFONTS view (the default
    * `GD_fontnames`): name → (family, weight, stretch, style, svgFamily).
    * gv's svg writer (`gvrender_core_svg.c:461`) matches the fontname
    * case-insensitively against this table; a hit emits
    * `font-family="family[,svgFamily]"` (+ weight/stretch/style attrs), a
    * miss emits the fontname VERBATIM — so the default "Times-Roman" prints
    * as "Times,serif" while a CSS-style list passes through untouched. */
  private val psAlias: Map[String, (String, String, String, String, String)] = Map(
    "avantgarde-book"             -> ("URW Gothic L", "book", "", "", "sans-Serif"),
    "avantgarde-bookoblique"      -> ("URW Gothic L", "book", "", "oblique", "sans-Serif"),
    "avantgarde-demi"             -> ("URW Gothic L", "demi", "", "", "sans-Serif"),
    "avantgarde-demioblique"      -> ("URW Gothic L", "demi", "", "oblique", "sans-Serif"),
    "bookman-demi"                -> ("URW Bookman L", "demi", "", "", "serif"),
    "bookman-demiitalic"          -> ("URW Bookman L", "demi", "", "italic", "serif"),
    "bookman-light"               -> ("URW Bookman L", "light", "", "", "serif"),
    "bookman-lightitalic"         -> ("URW Bookman L", "light", "", "italic", "serif"),
    "courier"                     -> ("Courier", "", "", "", "monospace"),
    "courier-bold"                -> ("Courier", "bold", "", "", "monospace"),
    "courier-boldoblique"         -> ("Courier", "bold", "", "oblique", "monospace"),
    "courier-oblique"             -> ("Courier", "", "", "oblique", "monospace"),
    "helvetica"                   -> ("Helvetica", "", "", "", "sans-Serif"),
    "helvetica-bold"              -> ("Helvetica", "bold", "", "", "sans-Serif"),
    "helvetica-boldoblique"       -> ("Helvetica", "bold", "", "oblique", "sans-Serif"),
    "helvetica-narrow"            -> ("Helvetica", "", "condensed", "", "sans-Serif"),
    "helvetica-narrow-bold"       -> ("Helvetica", "bold", "condensed", "", "sans-Serif"),
    "helvetica-narrow-boldoblique"-> ("Helvetica", "bold", "condensed", "oblique", "sans-Serif"),
    "helvetica-narrow-oblique"    -> ("Helvetica", "", "condensed", "oblique", "sans-Serif"),
    "helvetica-oblique"           -> ("Helvetica", "", "", "oblique", "sans-Serif"),
    "newcenturyschlbk-bold"       -> ("Century Schoolbook L", "bold", "", "", "serif"),
    "newcenturyschlbk-bolditalic" -> ("Century Schoolbook L", "bold", "", "italic", "serif"),
    "newcenturyschlbk-italic"     -> ("Century Schoolbook L", "", "", "italic", "serif"),
    "newcenturyschlbk-roman"      -> ("Century Schoolbook L", "roman", "", "", "serif"),
    "palatino-bold"               -> ("Palatino Linotype", "bold", "", "", "serif"),
    "palatino-bolditalic"         -> ("Palatino Linotype", "bold", "", "italic", "serif"),
    "palatino-italic"             -> ("Palatino Linotype", "", "", "italic", "serif"),
    "palatino-roman"              -> ("Palatino Linotype", "roman", "", "", "serif"),
    "symbol"                      -> ("Symbol", "", "", "", "fantasy"),
    "times-bold"                  -> ("Times", "bold", "", "", "serif"),
    "times-bolditalic"            -> ("Times", "bold", "", "italic", "serif"),
    "times-italic"                -> ("Times", "", "", "italic", "serif"),
    "times-roman"                 -> ("Times", "", "", "", "serif"),
    "zapfchancery-mediumitalic"   -> ("URW Chancery L", "medium", "", "italic", "serif"),
    "zapfdingbats"                -> ("Dingbats", "", "", "", "fantasy"))

  /** The `<text>` font attribute string for a fontname (see [[psAlias]]). */
  private def svgFontAttrs(fontName: String): String =
    psAlias.get(fontName.toLowerCase) match
      case Some((family, weight, stretch, style, svgFam)) =>
        val fam = if svgFam.nonEmpty && svgFam != family then s"$family,$svgFam" else family
        val sb  = new StringBuilder(s""" font-family="$fam"""")
        if weight.nonEmpty then sb ++= s""" font-weight="$weight""""
        if stretch.nonEmpty then sb ++= s""" font-stretch="$stretch""""
        if style.nonEmpty then sb ++= s""" font-style="$style""""
        sb.toString
      case None => s""" font-family="${fontName}""""
  private val LineSpacing  = 1.20  // LINESPACING
  private val ArrowLen     = 10.0  // ARROW_LENGTH (× arrowsize)
  private val GvVersion    = "13.0.1 (20250615.1724)"

  /** C `printf("%g")` with the default precision 6 (used for `<image>` attrs —
    * `gvloadimage_core.c` prints them raw, not through `gvprintdouble`). Chooses
    * `%f`-style for exponent ∈ [−4, 6) and strips trailing zeros; `%e`-style
    * otherwise. Rounds half-to-even, matching the C library's default. */
  private[output] def g6(x: Double): String =
    if x == 0.0 || (x > -1e-11 && x < 1e-11) then "0"
    else
      val a   = math.abs(x)
      var exp = math.floor(math.log10(a)).toInt          // ⌊log10|x|⌋
      if math.pow(10, exp) > a then exp -= 1             // fix log10 fp error
      if math.pow(10, exp + 1) <= a then exp += 1
      if exp < -4 || exp >= 6 then
        // %e style: mantissa to 5 decimals, strip zeros, e±NN (rare here).
        val m  = x / math.pow(10, exp)
        val ms = stripZeros(BigDecimal(m).setScale(5, BigDecimal.RoundingMode.HALF_EVEN).bigDecimal.toPlainString)
        val es = (if exp < 0 then "-" else "+") + f"${math.abs(exp)}%02d"
        s"${ms}e$es"
      else
        val frac = math.max(0, 5 - exp)                   // 6 sig figs ⇒ 5−exp decimals
        val s    = BigDecimal(x).setScale(frac, BigDecimal.RoundingMode.HALF_EVEN).bigDecimal.toPlainString
        stripZeros(s)

  /** `gvrender_usershape` (gvrender.c): place an image inside a target box
    * `[bllx,burx]×[blly,bury]` (world y-up), scaled per the `SCALE`/`imagescale`
    * value — `TRUE` fits preserving aspect (smaller axis scale), `WIDTH`/`HEIGHT`
    * fill that one axis, `BOTH` fills both, anything else (incl. default) is
    * `FALSE` (natural size). The image is then centred (imagepos "mc") in
    * whichever axis it ends up smaller than the box. `natW/natH` are the natural
    * pt dimensions (`gvusershape_size_dpi` at 72 dpi = the pt value). Emits the
    * `<image>` line (`gvloadimage_core`: width=UR.x−LL.x, height=UR.y−LL.y,
    * x=LL.x, y=−UR.y, %g-formatted, src raw). */
  private[output] def usershapeImage(src: String, bllx: Double, burx: Double,
                                     blly: Double, bury: Double,
                                     natW: Double, natH: Double, scale: Option[String],
                                     pos: String = "mc"): String =
    var (llx, urx, lly, ury) = (bllx, burx, blly, bury)
    val pw = urx - llx; val ph = ury - lly
    if natW > 0 && natH > 0 then
      var iw = natW; var ih = natH
      scale.map(_.toLowerCase) match
        case Some("true")   => val s = math.min(pw / natW, ph / natH); iw = natW * s; ih = natH * s
        case Some("width")  => iw = pw                 // fill width, natural height
        case Some("height") => ih = ph                 // fill height, natural width
        case Some("both")   => iw = pw; ih = ph        // fill both (no aspect)
        case _              => ()                      // false/default: natural size
      // imagepos (`<v><h>`, v∈t/m/b, h∈l/c/r; default mc) positions the image in
      // whichever axis it is smaller than the box — else it fills that axis.
      if iw < pw then pos.lift(1) match
        case Some('l') => urx = llx + iw
        case Some('r') => llx = urx - iw
        case _         => llx += (pw - iw) / 2.0; urx -= (pw - iw) / 2.0
      if ih < ph then pos.headOption match
        case Some('t') => lly = ury - ih
        case Some('b') => ury = lly + ih
        case _         => lly += (ph - ih) / 2.0; ury -= (ph - ih) / 2.0
    s"""<image xlink:href="$src" width="${g6(urx - llx)}px" height="${g6(ury - lly)}px" preserveAspectRatio="xMinYMin meet" x="${g6(llx)}" y="${g6(-ury)}"/>\n"""

  /** Drop a trailing fractional-zero run and a dangling decimal point. */
  private def stripZeros(s: String): String =
    if !s.contains('.') then s
    else
      var e = s.length
      while e > 0 && s.charAt(e - 1) == '0' do e -= 1
      if e > 0 && s.charAt(e - 1) == '.' then e -= 1
      val r = s.substring(0, e)
      if r == "-0" || r.isEmpty then "0" else r

  /** gvprintdouble: %.2f, trim trailing zeros & point, snap near-zero to 0. */
  private[output] def d2(x: Double): String =
    if x > -0.005 && x < 0.005 then "0"
    else
      val bd = BigDecimal(x).setScale(2, BigDecimal.RoundingMode.HALF_UP)
      var s  = bd.bigDecimal.toPlainString
      if s.contains('.') then
        s = s.reverse.dropWhile(_ == '0').dropWhile(_ == '.').reverse
      if s == "-0" then "0" else s

  /** Fixed "%.2f" (svg font-size — gv does NOT gvprintdouble-trim it). */
  private[output] def f2(x: Double): String =
    BigDecimal(x).setScale(2, BigDecimal.RoundingMode.HALF_UP).bigDecimal.toPlainString

  /** Graphviz xml_string for SVG: escape & < > " and '-' (avoids `--` in
    * XML comments; matches `a&#45;&gt;b` in titles/comments). */
  private def xml(s: String): String =
    val b = new StringBuilder
    s.foreach {
      case '&' => b ++= "&amp;"
      case '<' => b ++= "&lt;"
      case '>' => b ++= "&gt;"
      case '"' => b ++= "&quot;"
      case '-' => b ++= "&#45;"
      case c   => b += c
    }
    b.toString

  /** `gv_xml_escape` with the tooltip/anchor flag set `{raw, dash, nbsp}`
    * (`svg_begin_anchor`): `&` always escaped, `<`/`>`/`"`/`'` escaped, `-`
    * → `&#45;`, the 2nd+ of a consecutive space run → `&#160;`, `\n`/`\r`
    * → `&#10;`/`&#13;` (xml.c `xml_core`). */
  private def xmlTooltip(s: String): String =
    val b = new StringBuilder
    var prev = '\u0000'
    s.foreach { c =>
      c match
        case '&'                     => b ++= "&amp;"
        case '<'                     => b ++= "&lt;"
        case '>'                     => b ++= "&gt;"
        case '-'                     => b ++= "&#45;"
        case ' ' if prev == ' '      => b ++= "&#160;"
        case '"'                     => b ++= "&quot;"
        case '\''                    => b ++= "&#39;"
        case '\n'                    => b ++= "&#10;"
        case '\r'                    => b ++= "&#13;"
        case c                       => b += c
      prev = c
    }
    b.toString


  def svg(g: RGraph): String =
    val xs         = XCoord.xCoords(g)
    val (_, yOf)   = Coord.rankY(g)
    val ranks      = Rank.assign(g)
    val spl        = Spline.splinesEx(g)
    val labelPos   = Spline.labelPositions(g)
    val xlabels    = org.jpablo.graphexplorer.graphviz.layout.XLabels.place(g)
    // map_point (postproc.c): rotate canonical coords into the drawing frame
    // (identity for TB — the corpus is untouched). Every point below is drawn
    // at tf(canonical); node/label extents stay true-size (the rotation of the
    // swapped layout size gives back the true size), so only centres transform.
    val tf0 = org.jpablo.graphexplorer.graphviz.layout.DrawTransform.of(g)

    // graph bbox = the shared node/cluster box grown by spline extent
    // (Output.bbox / compute_bb). The `<svg width/height>`/viewBox are the
    // ceil'd int canvas; the `translate` and background polygon keep the exact
    // float (gv: int canvas, 2-dp bb).
    val (rlx, rly, rux, ruy) =
      if org.jpablo.graphexplorer.graphviz.layout.DrawTransform.rotated(g) then GraphBB.finalBBox(g)
      else { val (a, b, c, d) = GraphBB.bbox(g); (a.value, b.value, c.value, d.value) }
    // translate_drawing (postproc.c): shift the full bb to the origin (a no-op
    // unless a spline overhangs the node/cluster box — see Output.json0), so
    // coords + the `translate` land exactly like gv's.
    val dx = -rlx; val dy = -rly
    val tf: (Double, Double) => (Double, Double) = (x, y) => { val (a, b) = tf0(x, y); (a + dx, b + dy) }
    val (lx, ly, ux, uy) = (rlx + dx, rly + dy, rux + dx, ruy + dy)
    // gv canvas: ROUND((pageSize + 2*margin) * dpi/72) with dpi=72 (emit.c:1288)
    // ⇒ ROUND(bb + 2*margin). (Was ceil — same for ≥.5 fractions; triangle
    // 69.291 → 69, not ceil's 70.)
    // `pad` graph attr (emit.c:2926): "x[,y]" INCHES × 72 overrides the
    // default 4pt canvas pad on each axis.
    val (padX, padY) =
      g.rootAttrs.get("pad").flatMap { p =>
        val parts = p.split(",").map(_.trim)
        parts.headOption.flatMap(_.toDoubleOption).map { x =>
          (x * 72.0, parts.lift(1).flatMap(_.toDoubleOption).getOrElse(x) * 72.0)
        }
      }.getOrElse((Margin, Margin))
    val bbW = ux - lx; val bbH = uy - ly
    val w   = Output.gvRound(bbW + 2 * padX).toInt
    val h   = Output.gvRound(bbH + 2 * padY).toInt
    val trX = padX - lx
    val trY = uy + padY

    val sb = new StringBuilder
    sb ++= "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
    sb ++= "<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\"\n"
    sb ++= " \"http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd\">\n"
    sb ++= s"<!-- Generated by graphviz version $GvVersion\n -->\n"
    // `\E`/`\N`/title substitution (labels.c): a *named* graph emits the
    // `Title:` comment + a graph `<title>`; an anonymous graph (`%1`) emits
    // neither. (g.name == the graph id; None ⇒ anonymous.)
    val gname = g.name
    sb ++= (gname match
      case Some(nm) => s"<!-- Title: ${xml(nm)} Pages: 1 -->\n"
      case None     => "<!-- Pages: 1 -->\n")
    sb ++= s"""<svg width="${w}pt" height="${h}pt"\n"""
    sb ++= s""" viewBox="0.00 0.00 ${w}.00 ${h}.00" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">\n"""
    sb ++= s"""<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(${d2(trX)} ${d2(trY)})">\n"""
    gname.foreach(nm => sb ++= s"<title>${xml(nm)}</title>\n")
    // background canvas
    val bx0 = lx - padX; val bx1 = ux + padX
    val by0 = padY; val by1 = -(uy + padY)
    // canvas fill = `bgcolor` graph attr (lowercased hex) else white
    val bgFill = g.rootAttrs.get("bgcolor").filter(_.nonEmpty)
      .map(c => if c.startsWith("#") then c.toLowerCase else c).getOrElse("white")
    sb ++= s"""<polygon fill="$bgFill" stroke="none" points="${d2(bx0)},${d2(by0)} ${d2(bx0)},${d2(by1)} ${d2(bx1)},${d2(by1)} ${d2(bx1)},${d2(by0)} ${d2(bx0)},${d2(by0)}"/>\n"""

    // nodes (declaration order)
    def textAt(cx: Double, cyc: Double, s: String, fill: String = "",
               fontName: String = "Times-Roman", fontSize: Double = FontSize): String =
      val dimY = fontSize * LineSpacing
      val ty = -(cyc + dimY / 2.0 - fontSize + 0.1 * fontSize)
      val f  = if fill.nonEmpty then s""" fill="$fill"""" else "" // fontcolor
      // gv prints font-size with a fixed "%.2f" (not gvprintdouble's trim)
      s"""<text xml:space="preserve" text-anchor="middle" x="${d2(cx)}" y="${d2(ty)}"${svgFontAttrs(fontName)} font-size="${f2(fontSize)}"$f>${xml(s)}</text>\n"""

    /** Multi-line plain label centred at (cx, cyc), y-up (`emit_label` valign
      * 'c' + `svg_textspan`): split on `\n`/`\l`/`\r`, stack from the top
      * (`baseTop = cyc + dimen.y/2 − fontsize`, advancing by each line's own
      * height), anchor per justification (`n`→middle@cx, `l`→start@cx−w/2,
      * `r`→end@cx+w/2). Reduces to `textAt` for a single centred line. Empty
      * lines advance the cursor but draw nothing. */
    def textLines(cx: Double, cyc: Double, raw: String, fill: String,
                  nodeId: String, fontName: String, fontSize: Double = FontSize): String =
      val lines = NodeSize.labelLinesJust(raw, nodeId, g.name.getOrElse(""))
      if lines.forall(_._1.isEmpty) then return ""
      val dimenY = lines.map((l, _) => NodeSize.lineHeightPt(l, fontSize)).sum
      val boxW   = NodeSize.labelBoxWidthPt(raw, fontSize, fontName, nodeId, g.name.getOrElse(""))
      val f      = if fill.nonEmpty then s""" fill="$fill"""" else ""
      val out    = new StringBuilder
      var baseTop = cyc + dimenY / 2.0 - fontSize
      lines.foreach { case (line, just) =>
        val h  = NodeSize.lineHeightPt(line, fontSize)
        if line.nonEmpty then
          val ty = -(baseTop + 0.1 * fontSize)
          val (anchor, tx) = just match
            case 'l' => ("start", cx - boxW / 2.0)
            case 'r' => ("end",   cx + boxW / 2.0)
            case _   => ("middle", cx)
          out ++= s"""<text xml:space="preserve" text-anchor="$anchor" x="${d2(tx)}" y="${d2(ty)}"${svgFontAttrs(fontName)} font-size="${f2(fontSize)}"$f>${xml(line)}</text>\n"""
        baseTop -= h
      }
      out.toString

    // clusters (emit_clusters): each cluster draws a `<g class="cluster">`
    // with border polygon (LL→UL→UR→LR→LL) + its label, in GD_clust preorder,
    // after the background and before any node. TB clusters default to
    // fill=none / stroke=black; the label sits centred at lp (labelloc=t).
    locally {
      import org.jpablo.graphexplorer.graphviz.layout.Cluster
      val cls = Cluster.clusters(g)
      if cls.nonEmpty then
        val cbbs = Cluster.bbs(g)
        // name → RSubgraph, for the cluster's own graph attrs (style/colors).
        val sgByName =
          val b = Map.newBuilder[String, org.jpablo.graphexplorer.graphviz.model.RSubgraph]
          def walk(s: org.jpablo.graphexplorer.graphviz.model.RSubgraph): Unit =
            b += s.id -> s; s.children.foreach(walk)
          g.subgraphs.foreach(walk)
          b.result()
        cls.zipWithIndex.foreach { (c, i) =>
          val cb = cbbs(i)
          // translate_drawing shift (see header): cluster boxes bypass `tf`.
          val llx = cb.llx + dx; val urx = cb.urx + dx
          val lly = cb.lly + dy; val ury = cb.ury + dy
          sb ++= s"""<g id="clust${i + 1}" class="cluster">\n"""
          sb ++= s"<title>${xml(c.name)}</title>\n"
          // emit_clusters (emit.c): `style=filled` ⇒ FILL; `color` sets BOTH
          // fill+pen; `pencolor`/`fillcolor` override; `bgcolor` fills when not
          // already filled. Defaults: pen=black, fill=lightgrey (when filled).
          val a = sgByName.get(c.name).map(_.attrs).getOrElse(Map.empty)
          var filled    = a.get("style").exists(_.split(",").map(_.trim).contains("filled"))
          var pencolor  = Option.empty[String]
          var fillcolor = Option.empty[String]
          a.get("color").filter(_.nonEmpty).foreach { v => pencolor = Some(v); fillcolor = Some(v) }
          a.get("pencolor").filter(_.nonEmpty).foreach(v => pencolor = Some(v))
          a.get("fillcolor").filter(_.nonEmpty).foreach(v => fillcolor = Some(v))
          if (!filled || fillcolor.isEmpty) then
            a.get("bgcolor").filter(_.nonEmpty).foreach { v => fillcolor = Some(v); filled = true }
          val pen  = pencolor.getOrElse("black")
          val fill = if filled then fillcolor.getOrElse("lightgrey") else "none"
          // cluster `penwidth` ⇒ stroke-width (gvrender set_penwidth; svg
          // omits the attr at the default 1.0).
          val cpw = a.get("penwidth").flatMap(_.toDoubleOption).getOrElse(1.0)
          val sw  = if cpw != 1.0 then s""" stroke-width="${Output.g5(cpw)}"""" else ""
          sb ++= s"""<polygon fill="$fill" stroke="$pen"$sw points="${d2(llx)},${d2(-lly)} ${d2(llx)},${d2(-ury)} ${d2(urx)},${d2(-ury)} ${d2(urx)},${d2(-lly)} ${d2(llx)},${d2(-lly)}"/>\n"""
          if c.hasLabel then
            // label centre = lp (place_graph_label) — the formula lives on
            // CInfo.labelLp, shared with json0's `lp` attr.
            val (lpx, lpy) = c.labelLp(Cluster.BB(llx, lly, urx, ury))
            sb ++= textAt(lpx, lpy, c.label,
              fontName = a.getOrElse("fontname", "Times-Roman"))
          sb ++= "</g>\n"
        }
    }

    // colorxlate → the svg driver: `#RRGGBB` hex is emitted lowercase, SVG
    // color names pass through, and x11-only names resolve to hex — of those
    // only the gray/grey ramp (grayN = round(255·N/100)) has corpus exercise.
    def svgColor(c: String): String =
      if c.startsWith("#") then c.toLowerCase
      else
        "(?i)^gr[ae]y([0-9]{1,3})$".r.findFirstMatchIn(c.trim)
          .flatMap(_.group(1).toIntOption).filter(_ <= 100) match
          case Some(n) =>
            val v = math.round(255.0 * n / 100.0).toInt
            f"#$v%02x$v%02x$v%02x"
          case None => c

    // ── HTML-like label rendering (emit_html_txt / emit_htextspans) ──────────
    /** Render an HTML text block centred at (cx, cyc): each line is left-anchored
      * at its justified x, items laid left-to-right with per-run font styling.
      * The baseline reuses the quoted-label placement (gv's simple-text path);
      * multi-line stacks each line down by its height from the block top. */
    def htmlText(cx: Double, cyc: Double, block: org.jpablo.graphexplorer.graphviz.html.HtmlText,
                 defColor: String, alignWidth: Double,
                 defAlign: org.jpablo.graphexplorer.graphviz.html.HtmlAlign,
                 baseSize: Double, baseName: String): String =
      import org.jpablo.graphexplorer.graphviz.html.{HtmlLayout, HtmlAlign}
      val out = new StringBuilder
      // emit_htextspans (htmltable.c:116): the baseline cursor starts at the
      // box TOP and advances by each line's `lfsize`; the svg y is
      // −(baseline + yoffset_centerline) with yoffset = 0.1·fontsize for a
      // `simple` block and the constant 1 otherwise (svg_textspan adds it).
      val tm   = HtmlLayout.textLayout(block, baseSize, baseName)
      val boxW = alignWidth
      var baseline = cyc + tm.height / 2.0
      block.spans.zip(tm.lines).foreach { (sp, ln) =>
        baseline -= ln.lfsize
        val x0 = sp.align.getOrElse(defAlign) match
          case HtmlAlign.Left  => cx - boxW / 2.0
          case HtmlAlign.Right => cx + boxW / 2.0 - ln.width
          case _               => cx - ln.width / 2.0
        var xi = x0
        sp.items.foreach { it =>
          val fs   = it.font.size.getOrElse(baseSize)
          val nm   = it.font.name.getOrElse(baseName)
          val bold = it.font.bold || nm.toLowerCase.contains("bold")
          val ital = it.font.italic || nm.toLowerCase.contains("italic") || nm.toLowerCase.contains("oblique")
          val w    = HtmlLayout.itemWidth(it, baseSize, baseName)
          val wgt  = if bold then " font-weight=\"bold\"" else ""
          val sty  = if ital then " font-style=\"italic\"" else ""
          // <sub>/<sup> ⇒ SVG baseline-shift (same font size, same baseline).
          val bsh  = if it.font.sub then " baseline-shift=\"sub\""
                     else if it.font.sup then " baseline-shift=\"super\"" else ""
          val col  = it.font.color.orElse(Option(defColor).filter(_.nonEmpty))
          val f    = col.map(c => s""" fill="$c"""").getOrElse("")
          val dec  = if it.font.underline then " text-decoration=\"underline\"" else ""
          val yoffC = if tm.simple then 0.1 * fs else 1.0
          val ty    = -(baseline + yoffC)
          out ++= s"""<text xml:space="preserve" text-anchor="start" x="${d2(xi)}" y="${d2(ty)}"${svgFontAttrs(nm)}$wgt$sty$bsh font-size="${f"$fs%.2f"}"$f$dec>${xml(it.str)}</text>\n"""
          xi += w
        }
      }
      out.toString

    // Doc-wide gradient id counter (`l_N`) for `bgcolor="c0:c1"` fills.
    var htmlGradId = 0

    /** Render an HTML `<table>` centred at (cx, cyc): cells first (border box +
      * centred content), then the outer table border (emit order: cell, content,
      * …, table border last — matches gv). Coords are table-local y-up + centre. */
    def htmlTable(cx: Double, cyc: Double, tbl: org.jpablo.graphexplorer.graphviz.html.HtmlTable,
                  defColor: String, baseSize: Double, baseName: String,
                  fit: Option[(Double, Double)] = None): String =
      import org.jpablo.graphexplorer.graphviz.html.{HtmlTableLayout, HtmlLabel, HtmlAlign}
      val laid     = HtmlTableLayout.layout(tbl, baseSize, baseName, g.images, fit)
      val tblSpace = tbl.cellspacing.toDouble
      val out      = new StringBuilder
      // box polygon in world coords: LL, UL, UR, LR, LL (gvrender_box order).
      def boxPoly(b: HtmlTableLayout.BoxLocal): String =
        val (l, r) = (cx + b.llx, cx + b.urx)
        val (lo, hi) = (cyc + b.lly, cyc + b.ury)
        s"${d2(l)},${d2(-lo)} ${d2(l)},${d2(-hi)} ${d2(r)},${d2(-hi)} ${d2(r)},${d2(-lo)} ${d2(l)},${d2(-lo)}"
      // doBorder (htmltable.c:251): draw a cell/table border. A `sides` attr
      // (any of l/t/r/b; naming all four is ignored, per sidesfn) masks which
      // edges are stroked, as open polylines chained through the corners
      // SW→SE→NE→NW (mkPts order); otherwise the full box polygon. border > 1
      // insets the corners by border/2 and sets the pen width.
      def doBorder(attrs: Map[String, String], border: Int, b: HtmlTableLayout.BoxLocal): Unit =
        val stroke = attrs.get("color").getOrElse("black")
        val styles = attrs.get("style").map(_.split(",").iterator.map(_.trim.toLowerCase).toSet).getOrElse(Set.empty)
        val dash =
          if styles.contains("dashed") then """ stroke-dasharray="5,2""""
          else if styles.contains("dotted") then """ stroke-dasharray="1,5""""
          else ""
        val swA   = if border != 1 then s""" stroke-width="$border"""" else ""
        val delta = if border > 1 then border / 2.0 else 0.0
        val (l, r)   = (cx + b.llx + delta, cx + b.urx - delta)
        val (lo, hi) = (-(cyc + b.lly + delta), -(cyc + b.ury - delta))
        def pt(px: Double, py: Double) = s"${d2(px)},${d2(py)}"
        val (sw, se, ne, nw) = (pt(l, lo), pt(r, lo), pt(r, hi), pt(l, hi))
        def line(pts: String*): Unit =
          out ++= s"""<polyline fill="none" stroke="$stroke"$dash$swA points="${pts.mkString(" ")}"/>\n"""
        val mask = attrs.get("sides").map(_.toLowerCase.filter("ltrb".contains(_)).toSet).getOrElse(Set.empty)
        mask.toSeq.sorted.mkString match
          case "b"    => line(sw, se)
          case "r"    => line(se, ne)
          case "t"    => line(ne, nw)
          case "l"    => line(nw, sw)
          case "br"   => line(sw, se, ne)
          case "rt"   => line(se, ne, nw)
          case "lt"   => line(ne, nw, sw)
          case "bl"   => line(nw, sw, se)
          case "brt"  => line(sw, se, ne, nw)
          case "lrt"  => line(se, ne, nw, sw)
          case "blt"  => line(ne, nw, sw, se)
          case "blr"  => line(nw, sw, se, ne)
          case "bt"   => line(sw, se); line(ne, nw)
          case "lr"   => line(nw, sw); line(se, ne)
          case _      => // none or all four sides ⇒ the full closed box
            out ++= s"""<polygon fill="none" stroke="$stroke"$dash$swA points="$sw $nw $ne $se $sw"/>\n"""
      // Background fill: solid, or a two-colour left→right linear gradient when
      // bgcolor is `c0:c1` (a `<defs>` linearGradient across the box + url() ref).
      def bgFill(bg: String, box: HtmlTableLayout.BoxLocal): Unit =
        val parts = bg.split(":")
        if parts.length >= 2 then
          val id     = s"l_$htmlGradId"; htmlGradId += 1
          val (l, r) = (cx + box.llx, cx + box.urx)
          val gy     = -(cyc + box.cy)
          def col(s: String) = s.split(";").head
          out ++= s"""<defs>\n<linearGradient id="$id" gradientUnits="userSpaceOnUse" x1="${d2(l)}" y1="${d2(gy)}" x2="${d2(r)}" y2="${d2(gy)}" >\n"""
          out ++= s"""<stop offset="0" style="stop-color:${col(parts(0))};stop-opacity:1.;"/>\n"""
          out ++= s"""<stop offset="1" style="stop-color:${col(parts(1))};stop-opacity:1.;"/>\n"""
          out ++= "</linearGradient>\n</defs>\n"
          out ++= s"""<polygon fill="url(#$id)" stroke="none" points="${boxPoly(box)}"/>\n"""
        else
          out ++= s"""<polygon fill="$bg" stroke="none" points="${boxPoly(box)}"/>\n"""
      // table bgcolor fills the whole table box first (behind cells).
      val tblBox = HtmlTableLayout.BoxLocal(-laid.width / 2.0, -laid.height / 2.0, laid.width / 2.0, laid.height / 2.0)
      tbl.attrs.get("bgcolor").foreach(bg => bgFill(bg, tblBox))
      laid.cells.foreach { pc =>
        // cell bgcolor fill (no stroke) before the border.
        pc.cell.attrs.get("bgcolor").foreach(bg => bgFill(bg, pc.box))
        if pc.cellBorder > 0 then doBorder(pc.cell.attrs, pc.cellBorder, pc.box)
        val ccx = cx + pc.contentBox.cx
        // valign positions the content box within the (taller) content area:
        // top ⇒ content top at the area top, bottom ⇒ content bottom at the
        // area bottom, middle (default) ⇒ centred. (pos_html_cell alignment.)
        val (_, contentH) = org.jpablo.graphexplorer.graphviz.html.HtmlLayout.size(pc.cell.content, baseSize, baseName, g.images)
        val ccy = pc.cell.attrs.get("valign").map(_.toLowerCase) match
          case Some("top")    => cyc + pc.contentBox.ury - contentH / 2.0
          case Some("bottom") => cyc + pc.contentBox.lly + contentH / 2.0
          case _              => cyc + pc.contentBox.cy
        pc.cell.content match
          case HtmlLabel.Text(block)  =>
            val cw = pc.contentBox.urx - pc.contentBox.llx
            val al = pc.cell.attrs.get("align").map(_.toLowerCase) match
              case Some("left")  => HtmlAlign.Left
              case Some("right") => HtmlAlign.Right
              case _             => HtmlAlign.Center
            out ++= htmlText(ccx, ccy, block, defColor, cw, al, baseSize, baseName)
          case HtmlLabel.Table(inner) =>
            // pos_html_tbl: a nested table stretches into the cell's content box.
            out ++= htmlTable(ccx, ccy, inner, defColor, baseSize, baseName,
              Some((pc.contentBox.urx - pc.contentBox.llx, pc.contentBox.ury - pc.contentBox.lly)))
          case HtmlLabel.Image(src, scale) =>
            // Emit an `<image>` only when the dimensions are known (else gv can't
            // load the file and draws nothing — the missing-image case). The
            // target box is the cell content box; the shared usershape placer
            // handles SCALE + centring.
            g.images.get(src).foreach { dim =>
              out ++= usershapeImage(src,
                cx + pc.contentBox.llx, cx + pc.contentBox.urx,
                cyc + pc.contentBox.lly, cyc + pc.contentBox.ury,
                dim.w, dim.h, scale)
            }
      }
      // <hr/>/<vr/> rules: degenerate (zero-width/height) black polygons.
      // HR spans the full table width; VR the full height minus the bottom gap.
      val (tl, tr) = (cx - laid.width / 2.0, cx + laid.width / 2.0)
      val (tb, tt) = (cyc - laid.height / 2.0, cyc + laid.height / 2.0)
      laid.hrs.foreach { hy =>
        val y = -(cyc + hy)
        out ++= s"""<polygon fill="black" stroke="black" points="${d2(tl)},${d2(y)} ${d2(tl)},${d2(y)} ${d2(tr)},${d2(y)} ${d2(tr)},${d2(y)} ${d2(tl)},${d2(y)}"/>\n"""
      }
      laid.vrs.foreach { vx =>
        val x  = cx + vx
        val lo = -(tb + tblSpace); val hi = -tt
        out ++= s"""<polygon fill="black" stroke="black" points="${d2(x)},${d2(lo)} ${d2(x)},${d2(hi)} ${d2(x)},${d2(hi)} ${d2(x)},${d2(lo)} ${d2(x)},${d2(lo)}"/>\n"""
      }
      if laid.border > 0 then doBorder(tbl.attrs, laid.border, tblBox)
      out.toString

    // record_gencode + gen_fields (shapes.c): outer box polygon, then per
    // table the inter-child separator polylines + per leaf the field text.
    // Boxes are node-local (centre origin, y-up) — add the node centre.
    def genFields(f: org.jpablo.graphexplorer.graphviz.layout.RecordLabel.Field,
                   ncx: Double, ncy: Double,
                   fontName: String, fontSize: Double, fontColor: String = "",
                   nodeId: String = ""): Unit =
      import org.jpablo.graphexplorer.graphviz.html.{HtmlLabel, HtmlLayout, HtmlAlign}
      if f.isLeaf then
        val fcx = ncx + (f.llx + f.urx) / 2.0
        val fcy = ncy + (f.lly + f.ury) / 2.0
        f.htmlLbl match
          // HTML-in-record: the field label is LT_HTML — emit_label places it
          // at the field-box centre, the block justifies within its OWN box.
          case Some(HtmlLabel.Text(block)) =>
            val bw = HtmlLayout.textSize(block, fontSize, fontName)._1
            sb ++= htmlText(fcx, fcy, block, fontColor, bw, HtmlAlign.Center, fontSize, fontName)
          case Some(HtmlLabel.Table(tbl)) =>
            sb ++= htmlTable(fcx, fcy, tbl, fontColor, fontSize, fontName)
          case Some(HtmlLabel.Image(_, _)) => () // no corpus exercise
          case None =>
            f.text.filter(_.nonEmpty).foreach { t =>
              // field text renders in the NODE's font through the same label
              // machinery as node labels (gen_fields → emit_label of the
              // field's make_label lp): \n/\l/\r splits + \N substitution.
              sb ++= textLines(fcx, fcy, t, fontColor, nodeId, fontName, fontSize)
            }
      else
        f.flds.iterator.zipWithIndex.foreach { case (c, k) =>
          if k > 0 then
            val (a0, a1) =
              if f.lr then ((c.llx, c.lly), (c.llx, c.ury)) // vertical sep
              else        ((c.llx, c.ury), (c.urx, c.ury))  // horizontal sep
            sb ++= s"""<polyline fill="none" stroke="black" points="${d2(ncx + a0._1)},${d2(-(ncy + a0._2))} ${d2(ncx + a1._1)},${d2(-(ncy + a1._2))}"/>\n"""
          genFields(c, ncx, ncy, fontName, fontSize, fontColor, nodeId)
        }

    val op      = if g.directed then "->" else "--"
    val nodeIdx = g.nodes.iterator.map(_.id).zipWithIndex.toMap

    def emitNode(i: Int): Unit =
      val n = g.nodes(i)
      for xPt <- xs.get(n.id); sz <- NodeSize.nodeSize(n, g) do
        val (x, cy) = tf(xPt.value, yOf(ranks(n.id)).value)
        // getObjId (emit.c): explicit `id` attr, else `node{seq}` (== our
        // 1-based decl index — byte-exact across the corpus).
        val objId = n.attrs.get("id").filter(_.nonEmpty).getOrElse(s"node${i + 1}")
        sb ++= s"<!-- ${xml(n.id)} -->\n"
        sb ++= s"""<g id="node${i + 1}" class="node">\n"""
        sb ++= s"<title>${xml(n.id)}</title>\n"
        // emit_begin_node anchor: a node with a non-empty `href`/`URL`/`tooltip`
        // wraps its shape+label in `<g id="a_{objId}"><a …>…</a></g>`
        // (svg_begin_anchor / svg_end_anchor). href aliases URL; the tooltip
        // uses the raw/dash/nbsp escape set.
        val href    = n.attrs.get("href").orElse(n.attrs.get("URL")).filter(_.nonEmpty)
        val tooltip = n.attrs.get("tooltip").filter(_.nonEmpty)
        val target  = n.attrs.get("target").filter(_.nonEmpty)
        val anchored = href.isDefined || tooltip.isDefined
        if anchored then
          val a = new StringBuilder(s"""<g id="a_${xml(objId)}"><a""")
          href.foreach(h => a ++= s""" xlink:href="${xmlTooltip(h)}"""")
          tooltip.foreach(t => a ++= s""" xlink:title="${xmlTooltip(t)}"""")
          target.foreach(t => a ++= s""" target="${xml(t)}"""")
          a ++= ">\n"
          sb ++= a.toString
        NodeSize.recordLayout(n, g) match
          case Some(root) =>
            // outer record box (gvrender_box → svg polygon, LL/UL/UR/LR/LL);
            // record_gencode: style=filled ⇒ fill = fillcolor|color|lightgrey,
            // stroke = pencolor (`color`, default black).
            val styles = n.attrs.get("style").map(_.split(",").iterator.map(_.trim).toSet).getOrElse(Set.empty)
            val fill = svgColor(
              if styles.contains("filled") then
                n.attrs.get("fillcolor").orElse(n.attrs.get("color")).getOrElse("lightgrey")
              else "none")
            val stroke = svgColor(n.attrs.get("color").getOrElse("black"))
            val (llx, lly) = (x + root.llx, cy + root.lly)
            val (urx, ury) = (x + root.urx, cy + root.ury)
            sb ++= s"""<polygon fill="$fill" stroke="$stroke" points="${d2(llx)},${d2(-lly)} ${d2(llx)},${d2(-ury)} ${d2(urx)},${d2(-ury)} ${d2(urx)},${d2(-lly)} ${d2(llx)},${d2(-lly)}"/>\n"""
            genFields(root, x, cy,
              n.attrs.getOrElse("fontname", "Times-Roman"),
              n.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(FontSize),
              n.attrs.getOrElse("fontcolor", ""), n.id)
          case None =>
            val rx  = sz.halfWidthPt.value
            val ry  = sz.halfHeightPt.value
            val lbl = n.attrs.get("label").filter(_ != "\\N").getOrElse(n.id)
            // gv node style: `filled` ⇒ fill = fillcolor|color|lightgrey, else
            // "none"; stroke = pencolor (`color`), default black; text = fontcolor.
            val styles = n.attrs.get("style").map(_.split(",").iterator.map(_.trim).toSet).getOrElse(Set.empty)
            // shape=point (point_init): implicitly style=filled with the pen
            // colour, and an empty label — a small filled circle.
            val isPoint = n.attrs.get("shape").contains("point")
            val fill = svgColor(
              if isPoint then n.attrs.get("color").getOrElse("black")
              else if styles.contains("filled") then
                n.attrs.get("fillcolor").orElse(n.attrs.get("color")).getOrElse("lightgrey")
              else "none")
            val stroke = svgColor(n.attrs.get("color").getOrElse("black"))
            // penwidth ≠ 1 ⇒ every drawn outline gets stroke-width (gvrender
            // set_penwidth before the shape ops).
            val nodePw = n.attrs.get("penwidth").flatMap(_.toDoubleOption).getOrElse(1.0)
            // dashed/dotted node borders take stroke-dasharray exactly like
            // edges (gvrender pencil style; sbt's dashed boxes).
            val nodeDash =
              if styles.contains("dashed") then """ stroke-dasharray="5,2""""
              else if styles.contains("dotted") then """ stroke-dasharray="1,5""""
              else ""
            val swAttr =
              (if nodePw != 1.0 then s""" stroke-width="${Output.g5(nodePw)}"""" else "") + nodeDash
            // box-family shapes render as a rectangle <polygon> (corners
            // UR,UL,LL,LR,UR in flipped-y); `style=rounded` ⇒ a <path> with
            // RBCONST=12 corner arcs (shapes.c round_corners); else ellipse.
            val boxLike   = Set("box", "rect", "rectangle", "square", "image")
            val shapeName = n.attrs.get("shape").getOrElse("")
            val noShape   = Set("plaintext", "none", "plain").contains(shapeName)
            // node-local, y-up box corners (poly_init isBox vertex order:
            // TR,TL,BL,BR); shared by the special-corner + underline shapes.
            def af    = Vector((rx, ry), (-rx, ry), (-rx, -ry), (rx, -ry))
            def afSvg(p: (Double, Double)) = s"${d2(x + p._1)},${d2(-(cy + p._2))}"
            import org.jpablo.graphexplorer.graphviz.layout.RoundCorners
            if noShape then
              // plaintext/none/plain have peripheries=0 ⇒ normally no outline,
              // but poly_gencode (shapes.c:3011) still draws ONE box periphery
              // when filled, with a transparent pen — fill only, no stroke.
              if fill != "none" then
                sb ++= s"""<polygon fill="$fill" stroke="none" points="${(af :+ af.head).map(afSvg).mkString(" ")}"/>\n"""
            else if RoundCorners.codeOf.contains(shapeName) then
              // note/tab/folder/box3d/component + SBOL bio: round_corners on the
              // box AF, translated by the node centre (SVG negates y). Polygons
              // are closed by repeating vertex 0; polylines are open.
              RoundCorners(af, RoundCorners.codeOf(shapeName), fill != "none").foreach {
                case RoundCorners.Op.Poly(pts, f) =>
                  val ps = (pts :+ pts.head).map(afSvg).mkString(" ")
                  sb ++= s"""<polygon fill="${if f then fill else "none"}" stroke="$stroke"$swAttr points="$ps"/>\n"""
                case RoundCorners.Op.Line(pts) =>
                  sb ++= s"""<polyline fill="none" stroke="$stroke"$swAttr points="${pts.map(afSvg).mkString(" ")}"/>\n"""
              }
            else if shapeName == "underline" then
              // transparent-stroke box (poly_gencode set_pencolor "transparent")
              // + the drawn bottom edge AF[2]→AF[3].
              val boxPts = (af :+ af.head).map(afSvg).mkString(" ")
              sb ++= s"""<polygon fill="$fill" stroke="none" points="$boxPts"/>\n"""
              sb ++= s"""<polyline fill="none" stroke="$stroke"$swAttr points="${afSvg(af(2))} ${afSvg(af(3))}"/>\n"""
            else if shapeName == "Msquare" then
              // regular box + corner diagonals (diagonals_draw on the box AF).
              RoundCorners.diagonals(af, 4, fill != "none").foreach {
                case RoundCorners.Op.Poly(pts, f) =>
                  val ps = (pts :+ pts.head).map(afSvg).mkString(" ")
                  sb ++= s"""<polygon fill="${if f then fill else "none"}" stroke="$stroke"$swAttr points="$ps"/>\n"""
                case RoundCorners.Op.Line(pts) =>
                  sb ++= s"""<polyline fill="none" stroke="$stroke"$swAttr points="${pts.map(afSvg).mkString(" ")}"/>\n"""
              }
            else if boxLike.contains(shapeName) then
              val (l, rr)  = (x - rx, x + rx)
              val (t, b)   = (-(cy + ry), -(cy - ry))
              if styles.contains("rounded") then
                val c = math.min(12.0, math.min(rx, ry)) // RBCONST, clamped
                // 4 straight edges (cubic with endpoint controls) + 4 corner
                // arcs (control points at c/2), CW from the top edge.
                val segs = Seq(
                  s"${d2(rr - c)},${d2(t)} ${d2(l + c)},${d2(t)} ${d2(l + c)},${d2(t)}",
                  s"${d2(l + c / 2)},${d2(t)} ${d2(l)},${d2(t + c / 2)} ${d2(l)},${d2(t + c)}",
                  s"${d2(l)},${d2(t + c)} ${d2(l)},${d2(b - c)} ${d2(l)},${d2(b - c)}",
                  s"${d2(l)},${d2(b - c / 2)} ${d2(l + c / 2)},${d2(b)} ${d2(l + c)},${d2(b)}",
                  s"${d2(l + c)},${d2(b)} ${d2(rr - c)},${d2(b)} ${d2(rr - c)},${d2(b)}",
                  s"${d2(rr - c / 2)},${d2(b)} ${d2(rr)},${d2(b - c / 2)} ${d2(rr)},${d2(b - c)}",
                  s"${d2(rr)},${d2(b - c)} ${d2(rr)},${d2(t + c)} ${d2(rr)},${d2(t + c)}",
                  s"${d2(rr)},${d2(t + c / 2)} ${d2(rr - c / 2)},${d2(t)} ${d2(rr - c)},${d2(t)}"
                )
                sb ++= s"""<path fill="$fill" stroke="$stroke"$swAttr d="M${d2(rr - c)},${d2(t)}C${segs.mkString(" ")}"/>\n"""
              else
                // one rectangle per periphery (poly p_box, sides=4): innermost
                // first and filled, each outer ring GAP larger, outlines only.
                val peris = math.max(NodeSize.peripheriesOf(n), 1)
                var k = 0
                while k < peris do
                  val off = 4.0 * (peris - 1 - k) // GAP; inner ring is smallest
                  val (l2, rr2, t2, b2) = (l + off, rr - off, t + off, b - off)
                  val rf = if k == 0 then fill else "none"
                  sb ++= s"""<polygon fill="$rf" stroke="$stroke"$swAttr points="${d2(rr2)},${d2(t2)} ${d2(l2)},${d2(t2)} ${d2(l2)},${d2(b2)} ${d2(rr2)},${d2(b2)} ${d2(rr2)},${d2(t2)}"/>\n"""
                  k += 1
            else
              // Convex builtin polygon (diamond/triangle/hexagon/doubleoctagon/
              // egg/…): poly_gencode draws each drawn periphery translated by
              // the node centre, innermost first and filled, the rest unfilled
              // outlines (SVG negates y; each ring closed by repeating vertex 0).
              NodeSize.polygon(n, g) match
                case Some(poly) if shapeName == "cylinder" =>
                  // cylinder_draw: the 19-point body bezier + a 7-point top cap
                  // (AF[0..6] mirrored across y0 = AF[0].y). beziercurve ⇒
                  // <path d="M p0 C p1 p2 …"> with y negated at emit.
                  val afc = poly.rings.head
                  val body = s"M${afSvg(afc(0))}C${afc.drop(1).map(afSvg).mkString(" ")}"
                  sb ++= s"""<path fill="$fill" stroke="$stroke"$swAttr d="$body"/>\n"""
                  val y0  = afc(0)._2
                  val cap = Vector(afc(0)) ++ (1 to 5).map(k => (afc(k)._1, 2 * y0 - afc(k)._2)) :+ afc(6)
                  val capD = s"M${afSvg(cap(0))}C${cap.drop(1).map(afSvg).mkString(" ")}"
                  sb ++= s"""<path fill="none" stroke="$stroke"$swAttr d="$capD"/>\n"""
                case Some(poly) if shapeName == "Mdiamond" =>
                  // diamond + corner diagonals (diagonals_draw on the diamond AF).
                  RoundCorners.diagonals(poly.rings.head, 4, fill != "none").foreach {
                    case RoundCorners.Op.Poly(pts, f) =>
                      val ps = (pts :+ pts.head).map(afSvg).mkString(" ")
                      sb ++= s"""<polygon fill="${if f then fill else "none"}" stroke="$stroke"$swAttr points="$ps"/>\n"""
                    case RoundCorners.Op.Line(pts) =>
                      sb ++= s"""<polyline fill="none" stroke="$stroke"$swAttr points="${pts.map(afSvg).mkString(" ")}"/>\n"""
                  }
                case Some(poly) =>
                  poly.rings.zipWithIndex.foreach { (ring, j) =>
                    val pts    = ring.map((vx, vy) => s"${d2(x + vx)},${d2(-(cy + vy))}")
                    val closed = (pts :+ pts.head).mkString(" ")
                    val rf     = if j == 0 then fill else "none" // fill innermost only
                    sb ++= s"""<polygon fill="$rf" stroke="$stroke"$swAttr points="$closed"/>\n"""
                  }
                case None =>
                  // ellipse family: draw `peripheries` concentric rings from the
                  // inner (label-fit) outward; each ring is GAP larger (poly_gencode
                  // draws periphery 0 first). doublecircle ⇒ 2 rings (18, 22).
                  val peris = NodeSize.peripheriesOf(n)
                  val gap   = 4.0 // const.h GAP
                  var j     = 0
                  while j < peris do
                    val off = gap * (peris - 1 - j) // inner ring is smallest
                    sb ++= s"""<ellipse fill="$fill" stroke="$stroke"$swAttr cx="${d2(x)}" cy="${d2(-cy)}" rx="${d2(rx - off)}" ry="${d2(ry - off)}"/>\n"""
                    j += 1
                  if shapeName == "Mcircle" then
                    // Mcircle_hack: two horizontal chords near top/bottom
                    // (x=rw·0.6614, y=ht/2·0.75, on the unit circle x²+y²≈1).
                    val px = rx * 0.6614
                    val py = ry * 0.75
                    sb ++= s"""<polyline fill="none" stroke="$stroke"$swAttr points="${afSvg((px, py))} ${afSvg((-px, py))}"/>\n"""
                    sb ++= s"""<polyline fill="none" stroke="$stroke"$swAttr points="${afSvg((px, -py))} ${afSvg((-px, -py))}"/>\n"""
            // node `image=`: place the image inside the node's bounding box
            // (gvrender_usershape — natural size, centred), after the border and
            // before the label. Every bordered shape (box, ellipse, convex
            // polygon) centres the image in its `2rx × 2ry` bbox; a borderless
            // shape (plaintext/none/plain) is left to its own image path.
            if !noShape then
              n.attrs.get("image").filter(_.nonEmpty).foreach { src =>
                g.images.get(src).foreach { dim =>
                  // `gvrender_usershape` gets AF = the innermost-periphery
                  // vertices, so the placement box is their bounding box. For a
                  // convex polygon that differs from the node box (a triangle's
                  // vertices sit asymmetrically inside its bbox); box/ellipse
                  // fall back to the node box (2rx × 2ry).
                  val (bllx, burx, blly, bury) = NodeSize.polygon(n, g) match
                    case Some(poly) =>
                      val vx = poly.vertices.map(_._1); val vy = poly.vertices.map(_._2)
                      (x + vx.min, x + vx.max, cy + vy.min, cy + vy.max)
                    case None => (x - rx, x + rx, cy - ry, cy + ry)
                  sb ++= usershapeImage(src, bllx, burx, blly, bury,
                    dim.w, dim.h, n.attrs.get("imagescale"),
                    n.attrs.get("imagepos").getOrElse("mc"))
                }
              }
            // HTML-like label ⇒ render the parsed content; else the plain text.
            if n.attrs.isHtml("label") then
              import org.jpablo.graphexplorer.graphviz.html.{HtmlParser, HtmlLabel}
              HtmlParser.parse(n.attrs.getOrElse("label", "")) match
                case Some(HtmlLabel.Text(block)) =>
                  val nfs = n.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(14.0)
                  val nfn = n.attrs.getOrElse("fontname", "Times-Roman")
                  val bw = org.jpablo.graphexplorer.graphviz.html.HtmlLayout.textSize(block, nfs, nfn)._1
                  sb ++= htmlText(x, cy, block, n.attrs.get("fontcolor").getOrElse(""), bw,
                    org.jpablo.graphexplorer.graphviz.html.HtmlAlign.Center, nfs, nfn)
                case Some(HtmlLabel.Table(tbl)) =>
                  val nfs = n.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(14.0)
                  val nfn = n.attrs.getOrElse("fontname", "Times-Roman")
                  sb ++= htmlTable(x, cy, tbl, n.attrs.get("fontcolor").getOrElse(""), nfs, nfn)
                case Some(HtmlLabel.Image(src, _)) =>
                  // Bare-image node label: the drawn image, centred in the node.
                  g.images.get(src).foreach { dim =>
                    val (dw, dh) = dim.drawn
                    sb ++= s"""<image xlink:href="$src" width="${g6(dw)}px" height="${g6(dh)}px" preserveAspectRatio="xMinYMin meet" x="${g6(x - dw / 2.0)}" y="${g6(-(cy + dh / 2.0))}"/>\n"""
                  }
                case None                     => if lbl.nonEmpty then sb ++= textLines(x, cy, lbl, n.attrs.get("fontcolor").getOrElse(""), n.id, n.attrs.getOrElse("fontname", "Times-Roman"))
            // an empty label (`label=""`) draws no <text> (emit_label skips it);
            // shape=point has an implicit empty label.
            else if lbl.nonEmpty && !isPoint then
              sb ++= textLines(x, cy, lbl, n.attrs.get("fontcolor").getOrElse(""), n.id, n.attrs.getOrElse("fontname", "Times-Roman"))
        // external label (addXLabels): plain text at its placed centre,
        // emitted after the shape+label (emit_node xlabel order).
        n.attrs.get("xlabel").filter(_.nonEmpty).foreach { xl =>
          xlabels.nodes.get(n.id).foreach { p =>
            val (lx, ly) = tf(p.cx, p.cy)
            sb ++= textLines(lx, ly, xl, n.attrs.get("fontcolor").getOrElse(""), n.id,
              n.attrs.getOrElse("fontname", "Times-Roman"))
          }
        }
        if anchored then sb ++= "</a>\n</g>\n" // svg_end_anchor
        sb ++= "</g>\n"

    def emitEdge(ix: Int, ei: Int): Unit =
      val e = g.edges(ix)
      spl.get(ix).foreach { es => scala.util.boundary {
          // map_point every spline/arrow/label point into the drawing frame.
          val pts = es.pts.map(p => { val (x, y) = tf(p.x, p.y); Spline.XY(x, y) })
          // emit.c: the edge *comment* is portless (agnameof tail/head);
          // the `<title>` is the `\E` expansion = tail[:port]op head[:port]
          // where port = chkPort `.name` (after the first ':' if any, else
          // whole) = compass when a field+compass was given (`f2:s` ⇒ `s`).
          val comment = s"${e.tail}$op${e.head}"
          val tp = e.tailPortName.fold("")(":" + _)
          val hp = e.headPortName.fold("")(":" + _)
          val label = s"${e.tail}$tp$op${e.head}$hp"
          sb ++= s"<!-- ${xml(comment)} -->\n"
          // style=invis: the edge ranks/routes normally (json0 keeps its pos)
          // and its COMMENT prints, but the drawing <g> is skipped entirely.
          if e.attrs.get("style").exists(_.split(",").iterator.map(_.trim).contains("invis")) then
            scala.util.boundary.break()
          // getObjId (emit.c): an explicit `id` attr overrides `edge{seq}`
          // (ds declares `id = 0..16` on every edge).
          val edgeObjId = e.attrs.get("id").filter(_.nonEmpty).getOrElse(s"edge$ei")
          sb ++= s"""<g id="${xml(edgeObjId)}" class="edge">\n"""
          sb ++= s"<title>${xml(label)}</title>\n"
          val head = pts.head
          val rest = pts.tail.map(p => s"${d2(p.x)},${d2(-p.y)}").mkString(" ")
          // gv edge: stroke = `color` (default black); `dashed`/`dotted` style
          // ⇒ stroke-dasharray. Arrowhead polygon takes the same stroke+fill.
          val eStroke = e.attrs.get("color").getOrElse("black")
          val eStyles = e.attrs.get("style").map(_.split(",").iterator.map(_.trim).toSet).getOrElse(Set.empty)
          val dash =
            if eStyles.contains("dashed") then """ stroke-dasharray="5,2""""
            else if eStyles.contains("dotted") then """ stroke-dasharray="1,5""""
            else ""
          val ePw = e.attrs.get("penwidth").flatMap(_.toDoubleOption).getOrElse(1.0)
          val eSw = if ePw != 1.0 then s""" stroke-width="${Output.g5(ePw)}"""" else ""
          sb ++= s"""<path fill="none" stroke="$eStroke"$eSw$dash d="M${d2(head.x)},${d2(-head.y)}C$rest"/>\n"""
          // arrow_gen per drawn end (emit_edge_graphics: tail arrow at sp
          // with the FIRST spline point, then head arrow at ep with the
          // LAST) — arrow_flags decides which ends draw and with what name.
          def drawArrow(attach: Spline.XY, base: Spline.XY, name: String): Unit =
            val tip = { val (x, y) = tf(attach.x, attach.y); Spline.XY(x, y) }
            val dx = base.x - tip.x; val dy = base.y - tip.y
            val len = math.hypot(dx, dy)
            if len > 1e-9 then
              val pw = e.attrs.get("penwidth").flatMap(_.toDoubleOption).getOrElse(1.0)
              val as = e.attrs.get("arrowsize").flatMap(_.toDoubleOption).getOrElse(1.0)
              val (kind, open) = Arrow.kindOf(name)
              // arrow_gen (arrows.c): the arrowhead vector is EPSILON(1e-4)-
              // stabilized — `s = ARROW_LENGTH/(len + EPS)`, ±EPS added to
              // each component BEFORE scaling, then ×(lenfact·arrowsize)
              // (arrow_gen_type; lenfact 1.2 for diamond, 1.0 otherwise).
              // The nudge shifts polygon corners by ~1e-4pt — visible when
              // a corner lands exactly on a %.2f print boundary (sbt).
              val Eps = 0.0001
              val s   = ArrowLen / (len + Eps)
              val lf  = if kind == "diamond" then 1.2 else 1.0
              val u   = ((dx + (if dx >= 0.0 then Eps else -Eps)) * s * as * lf,
                         (dy + (if dy >= 0.0 then Eps else -Eps)) * s * as * lf)
              def pt(p: (Double, Double)) = s"${d2(p._1)},${d2(-p._2)}"
              // ARR_MOD_OPEN (`empty`/`odiamond`) ⇒ unfilled polygon.
              val fill = if open then "none" else eStroke
              kind match
                case "vee" =>
                  // crow ⇒ 8-point polygon a[0..7] (gvrender_polygon a,8,1)
                  val (a, _) = Arrow.crow0((tip.x, tip.y), u, as, pw)
                  val poly   = ((0 until 8).map(k => pt(a(k))) :+ pt(a(0))).mkString(" ")
                  sb ++= s"""<polygon fill="$eStroke" stroke="$eStroke" points="$poly"/>\n"""
                case "diamond" =>
                  val (a, _) = Arrow.diamond0((tip.x, tip.y), u, pw)
                  val poly   = ((0 until 4).map(k => pt(a(k))) :+ pt(a(0))).mkString(" ")
                  sb ++= s"""<polygon fill="$fill" stroke="$eStroke" points="$poly"/>\n"""
                case _ =>
                  val (a1, a2, a3, _) = Arrow.normal0((tip.x, tip.y), u, pw)
                  sb ++= s"""<polygon fill="$fill" stroke="$eStroke" points="${pt(a1)} ${pt(a2)} ${pt(a3)} ${pt(a1)}"/>\n"""
          val (sName, eName) = Arrow.flags(g.directed, e.attrs.get("dir"),
            e.attrs.get("arrowhead"), e.attrs.get("arrowtail"))
          sName.filter(_ != "none").foreach { nm => es.sp.foreach(spR => drawArrow(spR, pts.head, nm)) }
          eName.filter(_ != "none").foreach { nm => es.ep.foreach(epR => drawArrow(epR, pts.last, nm)) }
          // edge label text at its lp (make_chain label_vnode): HTML ⇒ the
          // parsed block, else a multi-line plain label (`\n`/`\l`/`\r`).
          e.attrs.get("label").filter(_.nonEmpty).foreach { lbl =>
            labelPos.get(ix).foreach { lp =>
              val (lpx, lpy) = tf(lp.x, lp.y)
              val col = e.attrs.get("fontcolor").getOrElse("")
              if e.attrs.isHtml("label") then
                import org.jpablo.graphexplorer.graphviz.html.{HtmlParser, HtmlLabel, HtmlLayout, HtmlAlign}
                HtmlParser.parse(lbl) match
                  case Some(HtmlLabel.Text(block)) =>
                    val efs = e.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(14.0)
                    val efn = e.attrs.getOrElse("fontname", "Times-Roman")
                    val bw = HtmlLayout.textSize(block, efs, efn)._1
                    sb ++= htmlText(lpx, lpy, block, col, bw, HtmlAlign.Center, efs, efn)
                  case Some(HtmlLabel.Table(tbl)) =>
                    val efs = e.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(14.0)
                    val efn = e.attrs.getOrElse("fontname", "Times-Roman")
                    sb ++= htmlTable(lpx, lpy, tbl, col, efs, efn)
                  case _                          => ()
              else
                sb ++= textLines(lpx, lpy, lbl, col, "", e.attrs.getOrElse("fontname", "Times-Roman"))
            }
          }
          // external edge label (addXLabels) after the regular label.
          e.attrs.get("xlabel").filter(_.nonEmpty).foreach { xl =>
            xlabels.edges.get(ix).foreach { p =>
              val (lx, ly) = tf(p.cx, p.cy)
              sb ++= textLines(lx, ly, xl, e.attrs.get("fontcolor").getOrElse(""), "",
                e.attrs.getOrElse("fontname", "Times-Roman"))
            }
          }
          sb ++= "</g>\n"
        }
      }

    // gv svg emit order: for each node (first-mention order) emit it, then per
    // out-edge emit the head node (if unseen) then the edge — so nodes/edges
    // interleave (a node appears just before the first edge that closes on it).
    // root graph label (do_graph_label): centered horizontally, single line,
    // labelloc bottom (default) at GAP + boxHeight/2, top at UR.y − GAP −
    // boxHeight/2 — in the GRAPH font. Emitted after the background, before
    // the nodes. (Custom fontsize / multi-line = tracked follow-ups.)
    g.rootAttrs.get("label").filter(_.nonEmpty).foreach { lbl =>
      val lh  = NodeSize.labelHeightPt(lbl, FontSize, g.name.getOrElse(""))
      val top = g.rootAttrs.get("labelloc").exists(_.startsWith("t"))
      val ly2 = if top then (uy - ly) - 4.0 - lh / 2.0 else 4.0 + lh / 2.0
      sb ++= textAt((lx + ux) / 2.0, ly2, lbl,
        fill = g.rootAttrs.getOrElse("fontcolor", ""),
        fontName = g.rootAttrs.getOrElse("fontname", "Times-Roman"))
    }

    // svg `id="edgeN"` = the edge's declaration (AGSEQ) index + 1 (g.edges is
    // in declaration order) — decoupled from the interleaved *emit* order.
    val emitted = scala.collection.mutable.Set.empty[String]
    def ensureNode(id: String): Unit =
      nodeIdx.get(id).foreach(i => if !emitted(id) then { emitted += id; emitNode(i) })
    // `agfstout` order: out-edges by HEAD node id (declaration), then AGSEQ —
    // not edge-declaration order (see Output.edgesByK). The `id="edgeN"`
    // stays the AGSEQ index (ix+1); only the emit sequence is head-ordered.
    // One O(E) per-tail grouping instead of an all-edges scan per node.
    val outEdgeIdxByTail: Map[String, Seq[Int]] =
      g.edges.indices.groupBy(ix => g.edges(ix).tail)
    g.nodes.indices.foreach { ti =>
      ensureNode(g.nodes(ti).id)
      outEdgeIdxByTail.getOrElse(g.nodes(ti).id, Seq.empty)
        .sortBy(ix => (nodeIdx.getOrElse(g.edges(ix).head, Int.MaxValue), ix))
        .foreach { ix =>
          ensureNode(g.edges(ix).head)
          emitEdge(ix, ix + 1)
        }
    }
    g.nodes.indices.foreach(i => ensureNode(g.nodes(i).id)) // any isolated nodes

    sb ++= "</g>\n</svg>\n"
    sb.toString

end Svg
