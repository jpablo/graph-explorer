package org.jpablo.graphexplorer.graphviz.output

import org.jpablo.graphexplorer.graphviz.model.RGraph
import org.jpablo.graphexplorer.graphviz.layout.{Coord, NodeSize, Rank, Spline, XCoord}

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
  private val LineSpacing  = 1.20  // LINESPACING
  private val ArrowLen     = 10.0  // ARROW_LENGTH (× arrowsize)
  private val GvVersion    = "13.0.1 (20250615.1724)"

  /** gvprintdouble: %.2f, trim trailing zeros & point, snap near-zero to 0. */
  private[output] def d2(x: Double): String =
    if x > -0.005 && x < 0.005 then "0"
    else
      val bd = BigDecimal(x).setScale(2, BigDecimal.RoundingMode.HALF_UP)
      var s  = bd.bigDecimal.toPlainString
      if s.contains('.') then
        s = s.reverse.dropWhile(_ == '0').dropWhile(_ == '.').reverse
      if s == "-0" then "0" else s

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

  private type P2 = (Double, Double)

  /** `miter_shape` (arrows.c): the stroke line-join triangle at `p` for the
    * segments `bl→p` and `p→br`; `points[0]` (`P3`) is the miter apex (or
    * the bevel midpoint past the SVG miterlimit 4). Penwidth-aware. */
  private def miterShape(bl: P2, p: P2, br: P2, pw: Double): P2 =
    if (bl == p) || (br == p) then p
    else
      val dxA = p._1 - bl._1; val dyA = p._2 - bl._2; val hA = math.hypot(dxA, dyA)
      val cosA = dxA / hA; val sinA = dyA / hA
      val P1   = (p._1 - pw / 2.0 * sinA, p._2 + pw / 2.0 * cosA)
      val dxB  = br._1 - p._1; val dyB = br._2 - p._2; val hB = math.hypot(dxB, dyB)
      val cosB = dxB / hB; val sinB = dyB / hB
      val alpha = if dyA > 0 then math.acos(cosA) else -math.acos(cosA)
      val beta  = if dyB > 0 then math.acos(cosB) else -math.acos(cosB)
      val betaRev = beta - math.Pi
      val theta = betaRev - alpha + (if betaRev - alpha <= -math.Pi then 2 * math.Pi else 0.0)
      val P2pt  = (p._1 + pw / 2.0 * (-sinB), p._2 - pw / 2.0 * (-cosB))
      if 1.0 / math.sin(theta / 2.0) > 4.0 then        // SVG stroke-miterlimit
        ((P1._1 + P2pt._1) / 2.0, (P1._2 + P2pt._2) / 2.0) // bevel midpoint
      else
        val l = pw / 2.0 / math.tan(theta / 2.0)
        (P1._1 + l * cosA, P1._2 + l * sinA)

  /** `arrow_type_normal0` (arrows.c) for the plain `normal` head (no
    * LEFT/RIGHT/INV/OPEN). `p` = arrow tip (`bezier.ep`), `u` = the
    * arrow-length vector pointing back up the edge. Returns the 3 polygon
    * points (`a[1..3]`) including the `delta_tip` miter shift — the sub-2px
    * M5/M7 residual, now ported (the returned spline-attach `q`/`delta_base`
    * is the spline pipeline's domain and unchanged here). */
  private def arrowNormal0(p: P2, u: P2, pw: Double): (P2, P2, P2) =
    val aw = if pw > 4 then 0.35 * pw / 4 else 0.35
    val v  = (-u._2 * aw, u._1 * aw)
    val q0 = (p._1 + u._1, p._2 + u._2)
    val bl = (-v._1, -v._2); val br = v
    val bigP = (-u._1, -u._2)
    val p3 = miterShape(bl, bigP, br, pw)
    val dtip = (p3._1 - bigP._1, p3._2 - bigP._2)
    val pp = (p._1 - dtip._1, p._2 - dtip._2)
    val qq = (q0._1 - dtip._1, q0._2 - dtip._2)
    ((qq._1 - v._1, qq._2 - v._2), pp, (qq._1 + v._1, qq._2 + v._2))

  def svg(g: RGraph): String =
    val byId       = g.nodes.iterator.map(n => n.id -> n).toMap
    val xs         = XCoord.xCoords(g)
    val (_, yOf)   = Coord.rankY(g)
    val ranks      = Rank.assign(g)
    val spl        = Spline.splinesEx(g)

    // graph bbox = the shared exact node-extent box (Output.bbox /
    // position.c dot_compute_bb) — NO spline, NO floor/ceil. `<svg
    // width/height>`/viewBox are the ceil'd int canvas; the `translate`
    // and background polygon keep the exact float (gv: int canvas, 2-dp bb).
    val (lx, ly, ux, uy) = Output.bbox(g)
    val bbW = ux - lx; val bbH = uy - ly
    val w   = math.ceil(bbW + 2 * Margin).toInt
    val h   = math.ceil(bbH + 2 * Margin).toInt
    val trX = Margin - lx
    val trY = uy + Margin

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
    val bx0 = lx - Margin; val bx1 = ux + Margin
    val by0 = Margin; val by1 = -(uy + Margin)
    sb ++= s"""<polygon fill="white" stroke="none" points="${d2(bx0)},${d2(by0)} ${d2(bx0)},${d2(by1)} ${d2(bx1)},${d2(by1)} ${d2(bx1)},${d2(by0)} ${d2(bx0)},${d2(by0)}"/>\n"""

    // nodes (declaration order)
    val dimY = FontSize * LineSpacing
    def textAt(cx: Double, cyc: Double, s: String): String =
      val ty = -(cyc + dimY / 2.0 - FontSize + 0.1 * FontSize)
      s"""<text xml:space="preserve" text-anchor="middle" x="${d2(cx)}" y="${d2(ty)}" font-family="Times,serif" font-size="14.00">${xml(s)}</text>\n"""

    // record_gencode + gen_fields (shapes.c): outer box polygon, then per
    // table the inter-child separator polylines + per leaf the field text.
    // Boxes are node-local (centre origin, y-up) — add the node centre.
    def genFields(f: org.jpablo.graphexplorer.graphviz.layout.RecordLabel.Field,
                   ncx: Double, ncy: Double): Unit =
      if f.isLeaf then
        f.text.filter(_.nonEmpty).foreach { t =>
          sb ++= textAt(ncx + (f.llx + f.urx) / 2.0, ncy + (f.lly + f.ury) / 2.0, t)
        }
      else
        f.flds.iterator.zipWithIndex.foreach { case (c, k) =>
          if k > 0 then
            val (a0, a1) =
              if f.lr then ((c.llx, c.lly), (c.llx, c.ury)) // vertical sep
              else        ((c.llx, c.ury), (c.urx, c.ury))  // horizontal sep
            sb ++= s"""<polyline fill="none" stroke="black" points="${d2(ncx + a0._1)},${d2(-(ncy + a0._2))} ${d2(ncx + a1._1)},${d2(-(ncy + a1._2))}"/>\n"""
          genFields(c, ncx, ncy)
        }

    g.nodes.zipWithIndex.foreach { case (n, i) =>
      for x <- xs.get(n.id); sz <- NodeSize.nodeSize(n, g) do
        val cy   = yOf(ranks(n.id))
        sb ++= s"<!-- ${xml(n.id)} -->\n"
        sb ++= s"""<g id="node${i + 1}" class="node">\n"""
        sb ++= s"<title>${xml(n.id)}</title>\n"
        NodeSize.recordLayout(n, g) match
          case Some(root) =>
            // outer record box (gvrender_box → svg polygon, LL/UL/UR/LR/LL)
            val (llx, lly) = (x + root.llx, cy + root.lly)
            val (urx, ury) = (x + root.urx, cy + root.ury)
            sb ++= s"""<polygon fill="none" stroke="black" points="${d2(llx)},${d2(-lly)} ${d2(llx)},${d2(-ury)} ${d2(urx)},${d2(-ury)} ${d2(urx)},${d2(-lly)} ${d2(llx)},${d2(-lly)}"/>\n"""
            genFields(root, x, cy)
          case None =>
            val rx  = sz.widthIn * 36.0
            val ry  = sz.heightIn * 36.0
            val lbl = n.attrs.get("label").filter(_ != "\\N").getOrElse(n.id)
            sb ++= s"""<ellipse fill="none" stroke="black" cx="${d2(x)}" cy="${d2(-cy)}" rx="${d2(rx)}" ry="${d2(ry)}"/>\n"""
            sb ++= textAt(x, cy, lbl)
        sb ++= "</g>\n"
    }

    // edges (cgraph node-traversal order)
    val op = if g.directed then "->" else "--"
    var ei = 0
    g.nodes.foreach { tnode =>
      g.edges.zipWithIndex.filter { case (e, _) => e.tail == tnode.id }.foreach { case (e, ix) =>
        ei += 1
        spl.get(ix).foreach { es =>
          val pts = es.pts
          // emit.c: the edge *comment* is portless (agnameof tail/head);
          // the `<title>` is the `\E` expansion = tail[:port]op head[:port]
          // where port = chkPort `.name` (after the first ':' if any, else
          // whole) = compass when a field+compass was given (`f2:s` ⇒ `s`).
          val comment = s"${e.tail}$op${e.head}"
          val tp = e.tailPortName.fold("")(":" + _)
          val hp = e.headPortName.fold("")(":" + _)
          val label = s"${e.tail}$tp$op${e.head}$hp"
          sb ++= s"<!-- ${xml(comment)} -->\n"
          sb ++= s"""<g id="edge$ei" class="edge">\n"""
          sb ++= s"<title>${xml(label)}</title>\n"
          val head = pts.head
          val rest = pts.tail.map(p => s"${d2(p.x)},${d2(-p.y)}").mkString(" ")
          sb ++= s"""<path fill="none" stroke="black" d="M${d2(head.x)},${d2(-head.y)}C$rest"/>\n"""
          if g.directed then
            es.ep.foreach { tip =>
              val base = pts.last
              val dx = base.x - tip.x; val dy = base.y - tip.y
              val len = math.hypot(dx, dy)
              if len > 1e-9 then
                val pw = e.attrs.get("penwidth").flatMap(_.toDoubleOption).getOrElse(1.0)
                val as = e.attrs.get("arrowsize").flatMap(_.toDoubleOption).getOrElse(1.0)
                val mag = ArrowLen * as
                val u   = (dx / len * mag, dy / len * mag)
                val (a1, a2, a3) = arrowNormal0((tip.x, tip.y), u, pw)
                def pt(p: (Double, Double)) = s"${d2(p._1)},${d2(-p._2)}"
                sb ++= s"""<polygon fill="black" stroke="black" points="${pt(a1)} ${pt(a2)} ${pt(a3)} ${pt(a1)}"/>\n"""
            }
          sb ++= "</g>\n"
        }
      }
    }

    sb ++= "</g>\n</svg>\n"
    sb.toString

end Svg
