package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.metrics.FontMetrics
import org.jpablo.graphexplorer.graphviz.html.{HtmlLayout, HtmlParser}
import org.jpablo.graphexplorer.graphviz.model.{RGraph, RNode}
import org.jpablo.graphexplorer.graphviz.units.Length
import org.jpablo.graphexplorer.graphviz.units.Length.{In, Pt}

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

  // ── per-graph geometry cache ──────────────────────────────────────────────
  // gv computes ND_lw/ND_rw/ND_ht (and the parsed label) ONCE per node in
  // common_init_node and stores them on the node struct; the port substituted
  // recomputation for those fields — a typical node was re-measured 8-12×
  // per render (Coord/XCoord/Spline/Output/Svg all re-derive sizes). These
  // GraphMemo-backed tables restore the C's compute-once shape. The inner
  // update is `synchronized` only to guard the mutable map under sbt's
  // concurrent suites (a no-op on Scala.js); the compute is pure, so a
  // racing double-compute is harmless.
  private val sizeMemo   = GraphMemo[scala.collection.mutable.HashMap[String, Option[Size]]]()
  private val recordMemo = GraphMemo[scala.collection.mutable.HashMap[String, Option[RecordLabel.Field]]]()
  private val polyMemo   = GraphMemo[scala.collection.mutable.HashMap[String, Option[Polygon.Poly]]]()
  private def cached[V](memo: GraphMemo[scala.collection.mutable.HashMap[String, V]], g: RGraph, id: String)(
      compute: => V): V =
    val m = memo(g)(scala.collection.mutable.HashMap.empty)
    m.synchronized(m.getOrElseUpdate(id, compute))

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

  final case class Size(width: In, height: In) derives CanEqual

  /** Convenience accessors so call sites don't repeat the `× 72` (full pt)
    * or `× 36` (half-pt) conversion that drives the `dot` layout pipeline —
    * the `Pt` return preserves type safety down to the very last `.value`
    * at the Double boundary (e.g. JSON output, ellipse rx/ry attributes). */
  extension (s: Size)
    def widthPt: Pt      = s.width.toPt
    def heightPt: Pt     = s.height.toPt
    def halfWidthPt: Pt  = s.width.toPt / 2.0
    def halfHeightPt: Pt = s.height.toPt / 2.0

  private final case class ShapeKind(
      box:         Boolean, // sides==4, axis-aligned → exact fit (no ellipse pad)
      regular:     Boolean, // equalise final w/h
      plain:       Boolean, // shape=plain: zero min size, no padding
      supported:   Boolean,
      peripheries: Int = 1  // concentric outlines (doublecircle=2); each adds GAP
  )

  private def shapeOf(name: String): ShapeKind = name.toLowerCase match
    case "ellipse" | "oval"            => ShapeKind(false, false, false, true)
    case "circle"                      => ShapeKind(false, true, false, true)
    case "doublecircle"                => ShapeKind(false, true, false, true, peripheries = 2)
    case "box" | "rect" | "rectangle"  => ShapeKind(true, false, false, true)
    case "image"                       => ShapeKind(true, false, false, true) // box that holds an image
    case "square"                      => ShapeKind(true, true, false, true)
    case "plaintext" | "none"          => ShapeKind(true, false, false, true)
    case "plain"                       => ShapeKind(true, false, true, true)
    case "point"                       => ShapeKind(false, true, false, true)
    // special-corner shapes (note/tab/folder/box3d/component + SBOL bio) and
    // underline all size as a plain sides=4 box (poly_init isBox); only their
    // drawn outline differs (RoundCorners at render time).
    case n if RoundCorners.codeOf.contains(n) || n == "underline"
                                       => ShapeKind(true, false, false, true)
    // M-variants: Msquare = regular box, Mcircle = regular circle (Mdiamond is
    // a diamond, routed via Polygon.descOf). Diagonals are a render-only add-on.
    case "msquare"                     => ShapeKind(true, true, false, true)
    case "mcircle"                     => ShapeKind(false, true, false, true)
    case _                             => ShapeKind(true, false, false, false)

  /** `point_init` (shapes.c): shorthand for circle/style=filled/label="". The
    * diameter (inches) = `min(late_double(width, ∞, MIN_NODEWIDTH),
    * late_double(height, ∞, MIN_NODEHEIGHT))`, defaulting to DEF_POINT=0.05
    * when neither is set, else clamped to ≥ MIN_POINT (0.0003) if positive. */
  private def pointDiamIn(n: RNode): Double =
    def ld(key: String, minv: Double): Double =
      n.attrs.get(key).flatMap(_.toDoubleOption) match
        case Some(v) => math.max(minv, v)
        case None    => Double.MaxValue
    val w = ld("width", 0.01)   // MIN_NODEWIDTH
    val h = ld("height", 0.02)  // MIN_NODEHEIGHT
    val m = math.min(w, h)
    if w == Double.MaxValue && h == Double.MaxValue then 0.05 // DEF_POINT
    else if m > 0.0 then math.max(m, 0.0003) else m // MIN_POINT

  /** Number of concentric peripheries actually drawn — shape default, overridden
    * by an explicit `peripheries` attr (late_int, ≥ 0). Consumed by both the
    * size (each extra periphery adds GAP to the radius) and the svg draw. */
  def peripheriesOf(n: RNode): Int =
    val name = n.attrs.getOrElse("shape", "ellipse")
    val base = Polygon.descOf(name).map(_.peripheries).getOrElse(shapeOf(name).peripheries)
    n.attrs.get("peripheries").flatMap(_.toIntOption).filter(_ >= 0).getOrElse(base)

  /** Effective polygon descriptor for a node: the builtin base
    * ([[Polygon.descOf]]) overlaid with the attributes `poly_init` reads —
    * `peripheries` and `orientation` for every polygon, and `sides`/`skew`/
    * `distortion` for the generic `polygon` shape (base `sides==0`). `regular`
    * OR-s in the `regular` attr. Mirrors `late_int`/`late_double` clamping. */
  def polyDescOf(n: RNode): Option[Polygon.Desc] =
    Polygon.descOf(n.attrs.getOrElse("shape", "ellipse")).map { base =>
      def ld(key: String, dflt: Double, lo: Double): Double =
        n.attrs.get(key).flatMap(_.toDoubleOption).map(math.max(lo, _)).getOrElse(dflt)
      def li(key: String, dflt: Int, lo: Int): Int =
        n.attrs.get(key).flatMap(_.toIntOption).map(math.max(lo, _)).getOrElse(dflt)
      val peripheries = li("peripheries", base.peripheries, 0)
      val orientation = base.orientation + ld("orientation", 0.0, -360.0)
      val regular     = base.regular || mapBool(n.attrs.get("regular"))
      if base.sides == 0 then
        base.copy(sides = li("sides", 4, 0), peripheries = peripheries, orientation = orientation,
                  distortion = ld("distortion", 0.0, -100.0), skew = ld("skew", 0.0, -100.0),
                  regular = regular)
      else base.copy(peripheries = peripheries, orientation = orientation, regular = regular)
    }

  /** Split a label into display lines, resolving `\n \l \r` breaks, `\\` →
    * `\`, and `\N`/`\G` substitutions. Justification is irrelevant to sizing.
    */
  private def labelLines(raw: String, nodeId: String, graphName: String): List[String] =
    labelLinesJust(raw, nodeId, graphName).map(_._1)

  /** Like [[labelLines]] but keeps each line's **justification** — `\n` ⇒
    * `'n'` (centre), `\l` ⇒ `'l'` (left), `\r` ⇒ `'r'` (right); the final
    * line (terminated by end-of-string, not an escape) is centre. This is
    * what `emit_label`/`svg_textspan` need to stack + anchor plain labels. */
  def labelLinesJust(raw: String, nodeId: String, graphName: String): List[(String, Char)] =
    val lines = scala.collection.mutable.ListBuffer.empty[(String, Char)]
    val cur   = new StringBuilder
    var i     = 0
    while i < raw.length do
      val c = raw.charAt(i)
      if c == '\\' && i + 1 < raw.length then
        raw.charAt(i + 1) match
          case j @ ('n' | 'l' | 'r') => lines += ((cur.toString, j)); cur.clear(); i += 2
          case '\\'                  => cur.append('\\'); i += 2
          case 'N'                   => cur.append(nodeId); i += 2
          case 'G'                   => cur.append(graphName); i += 2
          case other                 => cur.append(other); i += 2
      else
        cur.append(c); i += 1
    // a TRAILING escape produces NOTHING: `"x\n"` sizes and draws exactly
    // like `"x"` (gv's span builder emits no span after the final escape;
    // verified empirically against viz-js). Interior empties still count.
    if cur.nonEmpty || lines.isEmpty then lines += ((cur.toString, 'n'))
    lines.toList

  /** Per-line height in points (`make_label` `dimen.y` term): a non-empty
    * line is `fontSize*LINESPACING`, an empty one `(int)(fontSize*LINESPACING)`. */
  def lineHeightPt(line: String, fontSizePt: Double): Double =
    if line.isEmpty then (fontSizePt * LineSpacing).toInt.toDouble else fontSizePt * LineSpacing

  /** Widest rendered line width in points (`lp->space.x` for a plain label) —
    * drives `\l`/`\r` justification x-offset. */
  def labelBoxWidthPt(raw: String, fontSizePt: Double, fontName: String, nodeId: String, graphName: String): Double =
    labelLinesJust(raw, nodeId, graphName).map { case (l, _) =>
      if l.isEmpty then 0.0 else fontSizePt * FontMetrics.estimateTextWidth1pt(fontName, l, false, false)
    }.maxOption.getOrElse(0.0)

  private def dbl(n: RNode, key: String, default: Double): Double =
    n.attrs.get(key).flatMap(_.toDoubleOption).getOrElse(default)

  /** Graphviz `mapbool`: true/yes/1(+) ⇒ true; false/no/0/absent ⇒ false. */
  private[graphviz] def mapBool(v: Option[String]): Boolean = v match
    case Some(s) =>
      s.toLowerCase match
        case "true" | "yes" => true
        case "false" | "no" => false
        case other          => other.toIntOption.exists(_ > 0)
    case None => false

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

  /** Label box **width** in points = widest line (`estimate_textspan_size`).
    * Drives the graph-label `lwidth` + the label-driven bbox widening. */
  def labelWidthPt(raw: String, fontSizePt: Double, fontName: String = "Times", graphName: String = ""): Double =
    labelLines(raw, "", graphName).map { l =>
      if l.isEmpty then 0.0 else fontSizePt * FontMetrics.estimateTextWidth1pt(fontName, l, false, false)
    }.maxOption.getOrElse(0.0)

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
    cached(recordMemo, g, n.id)(recordLayoutImpl(n, g))
  private def recordLayoutImpl(n: RNode, g: RGraph): Option[RecordLabel.Field] =
    val sn = n.attrs.getOrElse("shape", "ellipse").toLowerCase
    if sn != "record" && sn != "mrecord" then return None
    val fixed = n.attrs.getOrElse("fixedsize", "false").toLowerCase match
      case "true" | "shape" => true
      case _                => false
    val (_, _, root) = RecordLabel.layout(
      n.attrs.getOrElse("label", "\\N"), !Rank.flip(g),
      dbl(n, "fontsize", DefFontSize), n.attrs.getOrElse("fontname", DefFontName),
      dbl(n, "width", DefWidthIn), dbl(n, "height", DefHeightIn), fixed, marginPt(n),
      html = n.attrs.isHtml("label"), imgs = g.images,
      nodeId = n.id, graphName = g.name.getOrElse("")
    )
    Some(root)

  /** Shared `poly_init` front-end: label box (points) after PAD/margin, the
    * min node size, and whether the label is vertically centred. Identical for
    * ellipse/box/polygon — only the downstream inflation differs. */
  private final case class Metrics(dimenX: Double, dimenY: Double, minW: Double, minH: Double, valignCentered: Boolean)

  private def polyMetrics(n: RNode, g: RGraph, shape: ShapeKind): Metrics =
    val fontSize = dbl(n, "fontsize", DefFontSize)
    val fontName = n.attrs.getOrElse("fontname", DefFontName)
    val fnCanon  = fontName.toLowerCase
    val bold     = fnCanon.contains("bold")
    val italic   = fnCanon.contains("italic") || fnCanon.contains("oblique")

    val rawLabel = n.attrs.getOrElse("label", "\\N")
    // label="" ⇒ NO label box at all (dimen 0,0 — an empty-label plain node
    // is a genuine 0×0 point in gv); a non-empty label's EMPTY LINES still
    // count (int)(fontsize·LINESPACING) each.
    val lines    = if rawLabel.isEmpty then Nil else labelLines(rawLabel, n.id, g.name.getOrElse(""))

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

    // HTML-like label: `make_html_label` sizes the parsed content box, which
    // then feeds the SAME poly_init PAD + fit — so a plain-text HTML label
    // sizes byte-identically to the equivalent quoted label. A markup that
    // fails to parse falls back to the raw string (gv reverts to simple label).
    if n.attrs.isHtml("label") then
      HtmlParser.parse(rawLabel).foreach { lbl =>
        val (w, h) = HtmlLayout.size(lbl, fontSize, fontName, g.images)
        dimenX = w; dimenY = h
      }

    // padding (only when there is a label, and not shape=plain)
    if (dimenX > 0 || dimenY > 0) && !shape.plain then
      n.attrs.get("margin") match
        case Some(mm) =>
          val parts = mm.split(",").toList.flatMap(_.trim.toDoubleOption)
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

    // node `image=`: the bb grows to hold the image — `bb = max(labelbox,
    // drawnimage + 2)` (shapes.c poly_init, `imagesize += 2` fixed padding).
    // Applies before the shape fit, so an ellipse then inflates this bb by
    // SQRT2 to *contain* the image. Drawn size = (int)(natural × 72/96), as the
    // cell path. The image is placed later by `Svg` (gvrender_usershape).
    n.attrs.get("image").filter(_.nonEmpty).flatMap(g.images.get).foreach { dim =>
      val (dw, dh) = dim.drawn
      dimenX = math.max(dimenX, dw + 2.0)
      dimenY = math.max(dimenY, dh + 2.0)
    }

    val wAttr = dbl(n, "width", DefWidthIn)
    val hAttr = dbl(n, "height", DefHeightIn)
    var minW  = PointsPerInch * (if shape.plain then 0.0 else wAttr)
    var minH  = PointsPerInch * (if shape.plain then 0.0 else hAttr)
    if shape.regular && !shape.plain then
      // regular (poly_init:1963 + userSize): if the user set width and/or
      // height, the square = their MAX (absent attr counts 0; a present one
      // clamps to MIN_NODEWIDTH/HEIGHT); else min of the defaults.
      val uw  = n.attrs.get("width").flatMap(_.toDoubleOption).map(math.max(_, 0.01)).getOrElse(0.0)
      val uh  = n.attrs.get("height").flatMap(_.toDoubleOption).map(math.max(_, 0.02)).getOrElse(0.0)
      val usz = math.max(uw, uh)
      val s   = PointsPerInch * (if usz > 0.0 then usz else math.min(wAttr, hAttr))
      minW = s; minH = s

    val valignCentered = !n.attrs.get("labelloc").map(_.charAt(0)).exists(c => c == 't' || c == 'b')
    Metrics(dimenX, dimenY, minW, minH, valignCentered)

  /** `fixedsize=true|shape` (poly_init: bb replaced by user width/height). */
  private def fixedSizeOf(n: RNode): Boolean =
    n.attrs.getOrElse("fixedsize", "false").toLowerCase match
      case "true" | "shape" => true
      case _                => false

  /** Convex builtin polygon geometry (final bb + centred y-up vertices), or
    * `None` for non-polygon shapes. Shares [[polyMetrics]] with [[nodeSize]] so
    * the size the layout uses and the vertices `Svg` draws stay consistent. */
  def polygon(n: RNode, g: RGraph): Option[Polygon.Poly] =
    cached(polyMemo, g, n.id)(polygonImpl(n, g))
  private def polygonImpl(n: RNode, g: RGraph): Option[Polygon.Poly] =
    polyDescOf(n).map { desc =>
      // poly_init:1951 — the `regular` ATTR ORs into the shape's flag.
      val reg   = desc.regular || mapBool(n.attrs.get("regular"))
      val shape = ShapeKind(box = false, regular = reg, plain = false, supported = true)
      val m     = polyMetrics(n, g, shape)
      Polygon.init(m.dimenX, m.dimenY, m.minW, m.minH, m.valignCentered, reg, desc,
        fixed = fixedSizeOf(n), penwidth = Polygon.attrPenwidth(n.attrs))
    }

  /** `ND_label(n)->space.y` (poly_init:2146): the vertical space available
    * to the label = PADDED label dimen + the growth from the minimum
    * label-holding box to the final (pre-periphery) box (+ image spare).
    * Drives labelloc=t/b emission (emit_label valign 't'/'b' read space.y).
    * Box/ellipse only; other shapes fall back to the padded dimen. */
  def labelSpaceY(n: RNode, g: RGraph): Double =
    val shapeName = n.attrs.getOrElse("shape", "ellipse")
    val regAttr   = mapBool(n.attrs.get("regular"))
    val shape0    = shapeOf(shapeName)
    val shape     = if regAttr && !shape0.regular then shape0.copy(regular = true) else shape0
    val m         = polyMetrics(n, g, shape)
    if !shape.supported then return m.dimenY
    var bbX = m.dimenX
    var bbY = m.dimenY
    if !shape.box then
      val temp = bbY * Sqrt2
      if m.minH > temp && m.valignCentered then
        bbX *= math.sqrt(1.0 / (1.0 - sqr(bbY / m.minH)))
      else
        bbX *= Sqrt2
        bbY = temp
    val minBbY = bbY
    val finalY = n.attrs.getOrElse("fixedsize", "false").toLowerCase match
      case "shape" | "true" => m.minH
      case _                => math.max(m.minH, bbY)
    m.dimenY + math.max(finalY - minBbY, 0.0)

  def nodeSize(n: RNode, g: RGraph): Option[Size] =
    cached(sizeMemo, g, n.id)(nodeSizeImpl(n, g))
  private def nodeSizeImpl(n: RNode, g: RGraph): Option[Size] =
    val shapeName = n.attrs.getOrElse("shape", "ellipse")
    val sn        = shapeName.toLowerCase
    if sn == "point" then
      val d = pointDiamIn(n) // circle: width == height == diameter, no label pad
      return Some(Size(In(d), In(d)))
    if sn == "record" || sn == "mrecord" then
      val fixed = n.attrs.getOrElse("fixedsize", "false").toLowerCase match
        case "true" | "shape" => true
        case _                => false
      val (w, h, _) = RecordLabel.layout(
        n.attrs.getOrElse("label", "\\N"), !Rank.flip(g),
        dbl(n, "fontsize", DefFontSize), n.attrs.getOrElse("fontname", DefFontName),
        dbl(n, "width", DefWidthIn), dbl(n, "height", DefHeightIn), fixed, marginPt(n),
        html = n.attrs.isHtml("label"), imgs = g.images,
        nodeId = n.id, graphName = g.name.getOrElse("")
      )
      return Some(Size(w, h))
    // Convex builtin polygons (diamond/triangle/hexagon/…) route through
    // [[Polygon]] for their own inflation + vertex-derived final size; treat
    // them as non-box (rotated/distorted) with an optional `regular` override.
    val polyDesc  = polyDescOf(n)
    // poly_init:1951 — the `regular` ATTR ORs into the shape's flag.
    val regAttr   = mapBool(n.attrs.get("regular"))
    val shape0    =
      if polyDesc.isDefined then ShapeKind(box = false, regular = polyDesc.get.regular, plain = false, supported = true)
      else shapeOf(shapeName)
    val shape     = if regAttr && !shape0.regular then shape0.copy(regular = true) else shape0
    if !shape.supported then return None

    val m = polyMetrics(n, g, shape)

    // Convex builtin polygon: Polygon.init does the SQRT2 + 1/cos(π/sides)
    // inflation and re-derives the final bb from the generated vertices
    // (including any concentric peripheries).
    polyDesc match
      case Some(desc) =>
        val p = Polygon.init(m.dimenX, m.dimenY, m.minW, m.minH, m.valignCentered, shape.regular, desc,
          fixed = fixedSizeOf(n), penwidth = Polygon.attrPenwidth(n.attrs))
        return Some(Size(In(p.bbX / PointsPerInch), In(p.bbY / PointsPerInch)))
      case None => ()

    var bbX = m.dimenX
    var bbY = m.dimenY

    if shape.box then
      () // axis-aligned box: label fit is exact
    else
      // smallest ellipse containing the label box, with the spare-height
      // optimisation Graphviz applies when valign is centred.
      val temp = bbY * Sqrt2
      if m.minH > temp && m.valignCentered then
        bbX *= math.sqrt(1.0 / (1.0 - sqr(bbY / m.minH)))
      else
        bbX *= Sqrt2
        bbY = temp

    n.attrs.getOrElse("fixedsize", "false").toLowerCase match
      case "shape" | "true" =>
        bbX = m.minW; bbY = m.minH
      case _ =>
        bbX = math.max(m.minW, bbX)
        bbY = math.max(m.minH, bbY)

    if shape.regular then
      val s = math.max(bbX, bbY)
      bbX = s; bbY = s

    // Peripheries (ellipse path, e.g. doublecircle): each extra concentric
    // ring adds GAP to the radius ⇒ 2*GAP to each dimension (poly_init ellipse
    // branch grows bb to 2*P where P += GAP per periphery).
    val peris = peripheriesOf(n)
    if peris > 1 then
      val grow = 2.0 * Gap * (peris - 1)
      bbX += grow; bbY += grow

    // NOTE: `nodeSize` is the *true* (rendered) size — the NodeSizeSpec/`dot`
    // oracle contract. Graphviz's `gv_nodesize(n, GD_flip)` w/h swap for
    // LR/RL is a layout-internal ND_lw/rw/ht mutation (restored by
    // `translate_drawing`); it must NOT be applied here. The LR layout-
    // orientation size belongs to a layout-size accessor (RankDir port —
    // tracked, PORT.md §5.2).
    Some(Size(In(bbX / PointsPerInch), In(bbY / PointsPerInch)))

  /** Layout-orientation size — `gv_nodesize(n, GD_flip)` (postproc.c). The
    * TB layout pipeline (Coord/XCoord/Spline) runs on this; for a flipped
    * graph (LR/RL) node w/h are **swapped** (`ND_ht = width`, `ND_lw =
    * ND_rw = height/2`), then `translate_drawing` rotates the result back
    * and `gv_nodesize(n, false)` restores the true size for drawing.
    *
    * `nodeSize` stays the **true** size (the `dot`-oracle contract gated by
    * NodeSizeSpec) — only the layout pipeline uses this swapped view, so
    * TB (`flip=false`) is byte-identical (`layoutSize == nodeSize`). */
  def layoutSize(n: RNode, g: RGraph): Option[Size] =
    nodeSize(n, g).map(s => if Rank.flip(g) then Size(s.height, s.width) else s)

  private inline def sqr(x: Double): Double = x * x

end NodeSize
