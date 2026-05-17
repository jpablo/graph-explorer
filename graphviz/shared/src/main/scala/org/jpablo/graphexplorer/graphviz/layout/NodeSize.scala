package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.metrics.FontMetrics
import org.jpablo.graphexplorer.graphviz.model.{RGraph, RNode}

/** Pure-Scala port of Graphviz `poly_init` node sizing (lib/common/shapes.c,
  * gv 13.0.1) for the poly shapes the corpus needs: ellipse, circle, box and
  * synonyms, square, plaintext/none, plain.
  *
  * Records / Mrecords / HTML-table labels return `None` — their recursive
  * field layout is M6. The result is the node bounding box in **inches**, the
  * same quantity Graphviz echoes as `width`/`height` in `dot` output, so it is
  * diffed directly against the oracle golden.
  */
object NodeSize:

  private val PointsPerInch = 72.0
  private val Gap           = 4.0          // const.h GAP
  private val XPad          = 4 * Gap      // macros.h XPAD: d.x += 4*GAP
  private val YPad          = 2 * Gap      // macros.h YPAD: d.y += 2*GAP
  private val LineSpacing   = 1.20         // const.h LINESPACING
  private val Sqrt2         = 1.41421356237309504880 // arith.h SQRT2
  private val DefFontSize   = 14.0         // const.h DEFAULT_FONTSIZE
  private val DefFontName   = "Times-Roman"
  private val DefWidthIn    = 0.75         // node `width` default
  private val DefHeightIn   = 0.5          // node `height` default

  final case class Size(widthIn: Double, heightIn: Double) derives CanEqual

  private final case class ShapeKind(
      box:       Boolean, // sides==4, axis-aligned → exact fit (no ellipse pad)
      regular:   Boolean, // equalise final w/h
      plain:     Boolean, // shape=plain: zero min size, no padding
      supported: Boolean
  )

  private def shapeOf(name: String): ShapeKind = name.toLowerCase match
    case "ellipse" | "oval"            => ShapeKind(false, false, false, true)
    case "circle"                      => ShapeKind(false, true, false, true)
    case "box" | "rect" | "rectangle"  => ShapeKind(true, false, false, true)
    case "square"                      => ShapeKind(true, true, false, true)
    case "plaintext" | "none"          => ShapeKind(true, false, false, true)
    case "plain"                       => ShapeKind(true, false, true, true)
    case _                             => ShapeKind(true, false, false, false)

  /** Split a label into display lines, resolving `\n \l \r` breaks, `\\` →
    * `\`, and `\N`/`\G` substitutions. Justification is irrelevant to sizing.
    */
  private def labelLines(raw: String, nodeId: String, graphName: String): List[String] =
    val lines = scala.collection.mutable.ListBuffer.empty[String]
    val cur   = new StringBuilder
    var i     = 0
    while i < raw.length do
      val c = raw.charAt(i)
      if c == '\\' && i + 1 < raw.length then
        raw.charAt(i + 1) match
          case 'n' | 'l' | 'r' => lines += cur.toString; cur.clear(); i += 2
          case '\\'            => cur.append('\\'); i += 2
          case 'N'             => cur.append(nodeId); i += 2
          case 'G'             => cur.append(graphName); i += 2
          case other           => cur.append(other); i += 2
      else
        cur.append(c); i += 1
    lines += cur.toString
    lines.toList

  private def dbl(n: RNode, key: String, default: Double): Double =
    n.attrs.get(key).flatMap(_.toDoubleOption).getOrElse(default)

  /** Label box height in **points** = Σ per-line height (`make_label`
    * `dimen.y`): `fontSizePt*LINESPACING` for a non-empty line,
    * `(int)(fontSizePt*LINESPACING)` for an empty one. Used to size edge
    * `label_vnode`s and the graph-label space. An HTML label is treated as
    * one line here (its table layout/width is a separate M6 deferral —
    * irrelevant to the rank-axis height, which is single-line). */
  def labelHeightPt(raw: String, fontSizePt: Double, graphName: String = ""): Double =
    labelLines(raw, "", graphName).map { l =>
      if l.isEmpty then (fontSizePt * LineSpacing).toInt.toDouble
      else fontSizePt * LineSpacing
    }.sum

  /** @return the node bounding box in inches, or `None` for shapes not yet
    *         ported (record/Mrecord/unknown).
    */
  /** Margin attr `"mx[,my]"` (inches) → points, else None ⇒ record PAD. */
  private def marginPt(n: RNode): Option[(Double, Double)] =
    n.attrs.get("margin").flatMap { s =>
      s.split(",").map(_.trim).flatMap(_.toDoubleOption) match
        case Array(mx, my) => Some((mx * PointsPerInch, my * PointsPerInch))
        case Array(mx)     => Some((mx * PointsPerInch, mx * PointsPerInch))
        case _             => None
    }

  /** Full record/Mrecord layout (boxes node-local, centre origin, y-up).
    * topLR = `!GD_realflip` (TB ⇒ horizontal top level). */
  def recordLayout(n: RNode, g: RGraph): Option[RecordLabel.Field] =
    val sn = n.attrs.getOrElse("shape", "ellipse").toLowerCase
    if sn != "record" && sn != "mrecord" then return None
    val fixed = n.attrs.getOrElse("fixedsize", "false").toLowerCase match
      case "true" | "shape" => true
      case _                => false
    val (_, _, root) = RecordLabel.layout(
      n.attrs.getOrElse("label", "\\N"), !Rank.flip(g),
      dbl(n, "fontsize", DefFontSize), n.attrs.getOrElse("fontname", DefFontName),
      dbl(n, "width", DefWidthIn), dbl(n, "height", DefHeightIn), fixed, marginPt(n)
    )
    Some(root)

  def nodeSize(n: RNode, g: RGraph): Option[Size] =
    val shapeName = n.attrs.getOrElse("shape", "ellipse")
    val sn        = shapeName.toLowerCase
    if sn == "record" || sn == "mrecord" then
      val fixed = n.attrs.getOrElse("fixedsize", "false").toLowerCase match
        case "true" | "shape" => true
        case _                => false
      val (w, h, _) = RecordLabel.layout(
        n.attrs.getOrElse("label", "\\N"), !Rank.flip(g),
        dbl(n, "fontsize", DefFontSize), n.attrs.getOrElse("fontname", DefFontName),
        dbl(n, "width", DefWidthIn), dbl(n, "height", DefHeightIn), fixed, marginPt(n)
      )
      return Some(Size(w, h))
    val shape     = shapeOf(shapeName)
    if !shape.supported then return None

    val fontSize = dbl(n, "fontsize", DefFontSize)
    val fontName = n.attrs.getOrElse("fontname", DefFontName)
    val fnCanon  = fontName.toLowerCase
    val bold     = fnCanon.contains("bold")
    val italic   = fnCanon.contains("italic") || fnCanon.contains("oblique")

    val rawLabel = n.attrs.getOrElse("label", "\\N")
    val lines    = labelLines(rawLabel, n.id, g.name.getOrElse(""))

    // estimate_textspan_size: width = fontsize * width@1pt; height per line =
    // fontsize*LINESPACING (non-empty) or (int)(fontsize*LINESPACING) (empty).
    val lineW = lines.map(l =>
      if l.isEmpty then 0.0 else fontSize * FontMetrics.estimateTextWidth1pt(fontName, l, bold, italic)
    )
    val lineH = lines.map(l =>
      if l.isEmpty then (fontSize * LineSpacing).toInt.toDouble else fontSize * LineSpacing
    )
    var dimenX = lineW.maxOption.getOrElse(0.0)
    var dimenY = lineH.sum

    // padding (only when there is a label, and not shape=plain)
    if (dimenX > 0 || dimenY > 0) && !shape.plain then
      n.attrs.get("margin") match
        case Some(m) =>
          val parts = m.split(",").toList.flatMap(_.trim.toDoubleOption)
          parts match
            case mx :: my :: _ =>
              dimenX += 2 * PointsPerInch * math.max(mx, 0)
              dimenY += 2 * PointsPerInch * math.max(my, 0)
            case mx :: Nil =>
              dimenX += 2 * PointsPerInch * math.max(mx, 0)
              dimenY += 2 * PointsPerInch * math.max(mx, 0)
            case Nil =>
              dimenX += XPad; dimenY += YPad
        case None =>
          dimenX += XPad; dimenY += YPad

    var bbX = dimenX
    var bbY = dimenY

    // min size from width/height attrs (regular ⇒ equal, min of the two)
    val wAttr = dbl(n, "width", DefWidthIn)
    val hAttr = dbl(n, "height", DefHeightIn)
    var minW  = PointsPerInch * (if shape.plain then 0.0 else wAttr)
    var minH  = PointsPerInch * (if shape.plain then 0.0 else hAttr)
    if shape.regular && !shape.plain then
      val s = PointsPerInch * math.min(wAttr, hAttr)
      minW = s; minH = s

    if shape.box then
      () // axis-aligned box: label fit is exact
    else
      // smallest ellipse containing the label box, with the spare-height
      // optimisation Graphviz applies when valign is centred.
      val valign = n.attrs.get("labelloc").map(_.charAt(0)).filter(c => c == 't' || c == 'b')
      val centred = valign.isEmpty
      val temp    = bbY * Sqrt2
      if minH > temp && centred then
        bbX *= math.sqrt(1.0 / (1.0 - sqr(bbY / minH)))
      else
        bbX *= Sqrt2
        bbY = temp

    n.attrs.getOrElse("fixedsize", "false").toLowerCase match
      case "shape" | "true" =>
        bbX = minW; bbY = minH
      case _ =>
        bbX = math.max(minW, bbX)
        bbY = math.max(minH, bbY)

    if shape.regular then
      val s = math.max(bbX, bbY)
      bbX = s; bbY = s

    // NOTE: `nodeSize` is the *true* (rendered) size — the NodeSizeSpec/`dot`
    // oracle contract. Graphviz's `gv_nodesize(n, GD_flip)` w/h swap for
    // LR/RL is a layout-internal ND_lw/rw/ht mutation (restored by
    // `translate_drawing`); it must NOT be applied here. The LR layout-
    // orientation size belongs to a layout-size accessor (RankDir port —
    // tracked, PORT.md §5.2).
    Some(Size(bbX / PointsPerInch, bbY / PointsPerInch))

  private inline def sqr(x: Double): Double = x * x

end NodeSize
