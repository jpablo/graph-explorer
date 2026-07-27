package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.dotlang.Compass
import org.jpablo.graphexplorer.graphviz.model.{RGraph, RNode}
import org.jpablo.graphexplorer.graphviz.units.Length.Pt

/** Port → anchor resolution: `lib/common/shapes.c` `record_port` +
  * `compassPort` (gv 13.0.1), scoped to record field ports (TB, no
  * rankdir flip — the rotation `cwrotatepf(p, 90·rankdir)` is identity).
  *
  * `record_port` maps the port *name* to its field via `map_rec_port`
  * (handled by `RecordLabel.fieldBox`), then `compassPort` turns the field
  * box + compass into a node-local point plus routing flags:
  *
  *  - no compass / `_` / `c`: `p` = box centre, `clip=true` (the spline is
  *    clipped to the field box — visible endpoint = box boundary on the
  *    exit side, resolved by the router);
  *  - `n`/`s`/`e`/`w`: side mid-point, `constrained` with a fixed tangent
  *    `theta`, `clip=false` (that point *is* the endpoint);
  *  - `ne`/`se`/`sw`/`nw`: the corresponding box corner.
  *
  * `p` is node-local (centre origin, y-up) — add `ND_coord(n)` for absolute.
  */
object PortAnchor:

  /** The port point as gv STORES it (shapes.c:2854): the true-frame anchor
    * `cwrotatepf`-rotated by `rankdir·90` into the CANONICAL frame, plus the
    * mincross ordinal `port.order` — `MC_SCALE/2` (128) at the origin, else
    * `(int)(MC_SCALE · angle/2π)` with 0 at the north pole increasing CCW.
    * Undefined ports get (0, 0) → (0.0, 128), matching gv's default port. */
  def canonical(n: RNode, g: RGraph,
                port: Option[org.jpablo.graphexplorer.graphviz.dotlang.Port]): (Double, Double, Int) =
    val a = port.flatMap(pp => resolve(n, g, pp.name.map(_.value).filter(_.nonEmpty), pp.compass))
    a match
      case None => (0.0, 0.0, 128)
      case Some(anch) =>
        val (tx, ty) = (anch.x.value, anch.y.value)
        val (px, py) = Rank.rankdir(g) match
          case RankDir.TB => (tx, ty)
          case RankDir.LR => (ty, -tx)  // cwrotatepf 90
          case RankDir.BT => (tx, -ty)  // 180
          case RankDir.RL => (ty, tx)   // 270
        val ord =
          if px == 0.0 && py == 0.0 then 128 // MC_SCALE/2
          else
            var ang = math.atan2(py, px) + 1.5 * math.Pi
            if ang >= 2.0 * math.Pi then ang -= 2.0 * math.Pi
            (256.0 * ang / (2.0 * math.Pi)).toInt
        (px, py, ord)


  final case class Anchor(
      x: Pt, y: Pt,                // node-local points (centre origin, y-up)
      clip: Boolean,               // spline clipped to the field box
      constrained: Boolean,        // fixed tangent at the endpoint
      theta: Double                // constrained tangent (radians; not a length)
  )

  private inline def anchor(x: Double, y: Double, clip: Boolean, constrained: Boolean, theta: Double): Anchor =
    Anchor(Pt(x), Pt(y), clip, constrained, theta)

  private val HalfPi = math.Pi / 2.0

  /** compassPort over a node-local field box `(llx,lly,urx,ury)` (y-up). */
  def fromBox(llx: Double, lly: Double, urx: Double, ury: Double, c: Option[Compass]): Anchor =
    val cx = (llx + urx) / 2.0
    val cy = (lly + ury) / 2.0
    import Compass.*
    c match
      case None | Some(Underscore) | Some(C) =>
        anchor(cx, cy, clip = true, constrained = false, 0.0)
      case Some(S)  => anchor(cx, lly, clip = false, constrained = true, -HalfPi)
      case Some(N)  => anchor(cx, ury, clip = false, constrained = true, HalfPi)
      case Some(E)  => anchor(urx, cy, clip = false, constrained = true, 0.0)
      case Some(W)  => anchor(llx, cy, clip = false, constrained = true, math.Pi)
      case Some(SE) => anchor(urx, lly, clip = false, constrained = true, -HalfPi / 2)
      case Some(SW) => anchor(llx, lly, clip = false, constrained = true, -3 * HalfPi / 2)
      case Some(NE) => anchor(urx, ury, clip = false, constrained = true, HalfPi / 2)
      case Some(NW) => anchor(llx, ury, clip = false, constrained = true, 3 * HalfPi / 2)

  /** Resolve `node:port[:compass]` to a node-local anchor, or `None` if the
    * node is not a record / the port name is unknown. */
  def resolve(n: RNode, g: RGraph, portName: Option[String], compass: Option[Compass]): Option[Anchor] =
    portName match
      case None => None // no field port (whole-node endpoint = existing path)
      case Some(name) =>
        val record =
          for
            root <- NodeSize.recordLayout(n, g)
            box  <- RecordLabel.fieldBox(root, name)
          yield fromBox(box._1, box._2, box._3, box._4, compass)
        record.orElse(htmlCellPort(n, name, compass))

  // ── full gv `port` struct for the SPLINE phase ───────────────────────────
  // shapes.c `record_port` → `compassPort` (initial resolution, possibly
  // `dyna`) and `resolvePort`/`closestSide` (route-time dyna resolution).
  // Everything is CANONICAL except `bp`, which stays in the node-local
  // TRUE/final frame exactly like gv's (`record_inside` ccw-rotates query
  // points back into it before the INSIDE test).

  final case class GvPort(
      px: Double, py: Double,     // canonical node-local port point (pp->p)
      theta: Double,              // canonical tangent (invflip_angle)
      constrained: Boolean,
      clip: Boolean,
      side: Int,                  // canonical side bits; a dyna port keeps RAW true-frame sides
      defined: Boolean,
      dyna: Boolean,
      bp: Option[(Double, Double, Double, Double)]) // TRUE-frame field box (node-local)

  object GvPort:
    /** `Center` (shapes.c:36) — the portless default: clip to the node. */
    val center: GvPort = GvPort(0.0, 0.0, -1.0, constrained = false, clip = true,
                                side = 0, defined = false, dyna = false, bp = None)

  // geom.h side bits (same values as RecordLabel's)
  private val SBottom = 1
  private val SRight  = 2
  private val STop    = 4
  private val SLeft   = 8
  private val SAll    = SBottom | SRight | STop | SLeft

  /** shapes.c `invflip_side`: final-frame side → canonical side. */
  def invflipSide(side: Int, rd: RankDir): Int = rd match
    case RankDir.TB => side
    case RankDir.BT =>
      side match
        case STop => SBottom; case SBottom => STop; case s => s
    case RankDir.LR =>
      side match
        case STop => SRight; case SBottom => SLeft
        case SLeft => STop;  case SRight  => SBottom
        case s => s
    case RankDir.RL =>
      side match
        case STop => SRight; case SBottom => SLeft
        case SLeft => SBottom; case SRight => STop
        case s => s

  /** shapes.c `invflip_angle`: final-frame tangent → canonical. */
  def invflipAngle(angle: Double, rd: RankDir): Double = rd match
    case RankDir.TB => angle
    case RankDir.BT => -angle
    case RankDir.LR => angle - math.Pi * 0.5
    case RankDir.RL =>
      if angle == math.Pi then -0.5 * math.Pi
      else if angle == math.Pi * 0.75 then -0.25 * math.Pi
      else if angle == math.Pi * 0.5 then 0.0
      else if angle == 0.0 then math.Pi * 0.5
      else if angle == math.Pi * -0.25 then math.Pi * 0.75
      else if angle == math.Pi * -0.5 then math.Pi
      else angle

  /** geomprocs `cwrotatepf(p, 90·rankdir)`: true/final frame → canonical. */
  def cwrot(x: Double, y: Double, rd: RankDir): (Double, Double) = rd match
    case RankDir.TB => (x, y)
    case RankDir.LR => (y, -x)
    case RankDir.BT => (x, -y)
    case RankDir.RL => (y, x)

  /** geomprocs `ccwrotatepf`: canonical → true/final frame (the
    * `record_inside` clip frame). Inverse of [[cwrot]]. */
  def ccwrot(x: Double, y: Double, rd: RankDir): (Double, Double) = rd match
    case RankDir.TB => (x, y)
    case RankDir.LR => (-y, x)
    case RankDir.BT => (x, -y)
    case RankDir.RL => (y, x)

  /** shapes.c `cvtPt`: canonical → final frame (closestSide distances). */
  def cvtPt(x: Double, y: Double, rd: RankDir): (Double, Double) = rd match
    case RankDir.TB => (x, y)
    case RankDir.BT => (x, -y)
    case RankDir.LR => (-y, x)
    case RankDir.RL => (y, x)

  /** shapes.c `compassPort` over a TRUE-frame field box (the ictxt-less
    * branch), full port struct. `compass = None` is the C NULL compass
    * (centre port, `clip = true`); `_` is the dyna port. */
  def compassPortFull(box: (Double, Double, Double, Double), sides: Int,
                      compass: Option[Compass], rd: RankDir): GvPort =
    val (llx, lly, urx, ury) = box
    val ctrx = (llx + urx) / 2.0
    val ctry = (lly + ury) / 2.0
    var px = ctrx; var py = ctry
    var theta = 0.0
    var constrain = false; var dyna = false
    var side = 0; var clip = true
    import Compass.*
    compass match
      case Some(E)  => px = urx; theta = 0.0; constrain = true; clip = false; side = sides & SRight
      case Some(S)  => py = lly; px = ctrx; theta = -math.Pi * 0.5; constrain = true; clip = false; side = sides & SBottom
      case Some(SE) => py = lly; px = urx; theta = -math.Pi * 0.25; constrain = true; clip = false; side = sides & (SBottom | SRight)
      case Some(SW) => py = lly; px = llx; theta = -math.Pi * 0.75; constrain = true; clip = false; side = sides & (SBottom | SLeft)
      case Some(W)  => px = llx; theta = math.Pi; constrain = true; clip = false; side = sides & SLeft
      case Some(N)  => py = ury; px = ctrx; theta = math.Pi * 0.5; constrain = true; clip = false; side = sides & STop
      case Some(NE) => py = ury; px = urx; theta = math.Pi * 0.25; constrain = true; clip = false; side = sides & (STop | SRight)
      case Some(NW) => py = ury; px = llx; theta = math.Pi * 0.75; constrain = true; clip = false; side = sides & (STop | SLeft)
      case Some(Underscore) => dyna = true; side = sides
      case Some(C) | None   => ()
    val (cpx, cpy) = cwrot(px, py, rd)
    GvPort(cpx, cpy, invflipAngle(theta, rd), constrain, clip,
           if dyna then side else invflipSide(side, rd),
           defined = true, dyna = dyna, bp = Some(box))

  private def compassOfName(s: String): Option[Compass] =
    import Compass.*
    s match
      case "n" => Some(N); case "ne" => Some(NE); case "e" => Some(E)
      case "se" => Some(SE); case "s" => Some(S); case "sw" => Some(SW)
      case "w" => Some(W); case "nw" => Some(NW); case "c" => Some(C)
      case "_" => Some(Underscore); case _ => None

  /** shapes.c `record_port` for the spline phase: field box + accessible
    * sides → `compassPort` (no compass ⇒ `_` ⇒ dyna). An unresolved port
    * NAME is treated as a compass over the whole record box (shapes.c:3775);
    * an unrecognized one degrades to a centre port over that box (the C
    * warns and leaves the centre defaults). Non-record nodes → None. */
  def gvRecordPort(n: RNode, g: RGraph, port: org.jpablo.graphexplorer.graphviz.dotlang.Port): Option[GvPort] =
    val rd = Rank.rankdir(g)
    port.name.map(_.value).filter(_.nonEmpty).flatMap { name =>
      NodeSize.recordLayout(n, g).map { root =>
        RecordLabel.field(root, name) match
          case Some(f) =>
            compassPortFull((f.llx, f.lly, f.urx, f.ury), f.sides,
                            port.compass.orElse(Some(Compass.Underscore)), rd)
          case None =>
            val rootBox = (root.llx, root.lly, root.urx, root.ury)
            compassPortFull(rootBox, SAll, compassOfName(name).orElse(Some(Compass.C)), rd)
      }
    }

  /** shapes.c `resolvePort`/`closestSide` (route time): a dyna port picks the
    * accessible field-box side whose midpoint (FINAL frame, `cvtPt`) is
    * closest to `other`, then re-resolves through `compassPort`; no/all sides
    * ⇒ the centre port. Sides tried in BOTTOM, RIGHT, TOP, LEFT order with a
    * strict `<` (first wins ties). */
  def resolveDyna(gp: GvPort, rd: RankDir,
                  selfCanon: (Double, Double), otherCanon: (Double, Double)): GvPort =
    gp.bp match
      case None => gp
      case Some(bp) => resolveDynaBp(gp, bp, rd, selfCanon, otherCanon)

  private def resolveDynaBp(gp: GvPort, bp: (Double, Double, Double, Double), rd: RankDir,
                            selfCanon: (Double, Double), otherCanon: (Double, Double)): GvPort =
    val sides = gp.side
    val compass: Option[Compass] =
      if sides == 0 || sides == SAll then None
      else
        val (llx, lly, urx, ury) = bp
        val (ptx, pty) = cvtPt(selfCanon._1, selfCanon._2, rd)
        val (otx, oty) = cvtPt(otherCanon._1, otherCanon._2, rd)
        var best = -1; var mind = 0.0
        var i = 0
        while i < 4 do
          if (sides & (1 << i)) != 0 then
            val (mx, my) = i match
              case 0 => ((llx + urx) / 2.0, lly)          // BOTTOM → s
              case 1 => (urx, (lly + ury) / 2.0)          // RIGHT  → e
              case 2 => ((llx + urx) / 2.0, ury)          // TOP    → n
              case _ => (llx, (lly + ury) / 2.0)          // LEFT   → w
            val dx = ptx + mx - otx; val dy = pty + my - oty
            val d = dx * dx + dy * dy
            if best < 0 || d < mind then { mind = d; best = i }
          i += 1
        import Compass.*
        Vector(S, E, N, W).lift(best)
    compassPortFull(bp, sides, compass, rd)

  /** shapes.c `poly_port` html branch (shapes.c:2890): `html_port` resolves
    * the `<td port="name">` cell box + its node-boundary `sides` mask, then
    * the SAME `compassPort` as records (no compass ⇒ `_` ⇒ dyna, resolved at
    * route time against the other endpoint). The cell box is table-local
    * centred on the table — and the table on the node — so it doubles as the
    * node-local port box. Non-html labels / unknown port names → None. */
  def gvHtmlPort(n: RNode, g: RGraph, port: org.jpablo.graphexplorer.graphviz.dotlang.Port): Option[GvPort] =
    import org.jpablo.graphexplorer.graphviz.html.{HtmlParser, HtmlLabel, HtmlTableLayout}
    val rd = Rank.rankdir(g)
    port.name.map(_.value).filter(_.nonEmpty).flatMap { name =>
      if !n.attrs.isHtml("label") then None
      else
        val fs = n.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(14.0)
        val fn = n.attrs.getOrElse("fontname", "Times")
        HtmlParser.parse(n.attrs.getOrElse("label", ""))
          .collect { case HtmlLabel.Table(tbl) => tbl }
          .flatMap(tbl => HtmlTableLayout.cellPortBoxSides(tbl, name, fs, fn, g.images))
          .map { (box, sides) =>
            compassPortFull((box.llx, box.lly, box.urx, box.ury), sides,
                            port.compass.orElse(Some(Compass.Underscore)), rd)
          }
    }

  /** poly_port fallback (shapes.c:2905): a port on a PLAIN (non-record,
    * non-HTML) node treats the port token as a COMPASS over the whole node
    * box (compassPort with bp=NULL — the final-frame node extents; port.bp
    * stays UNSET, so clipping uses the node shape). An ellipse's e/w/n/s
    * land on the side midpoints — identical to compassPoint's axis-ray
    * boundary hits (diagonal compasses would need the ellipse bisection;
    * no corpus exercise). Non-compass tokens → None (gv warns → Center). */
  def gvPolyPort(n: RNode, g: RGraph, port: org.jpablo.graphexplorer.graphviz.dotlang.Port): Option[GvPort] =
    val rd = Rank.rankdir(g)
    val cs = port.compass.orElse(port.name.map(_.value).flatMap(compassOfName))
    cs.flatMap { c =>
      NodeSize.nodeSize(n, g).map { sz =>
        val w2 = sz.widthPt.value / 2.0
        val h2 = sz.heightPt.value / 2.0
        val base = compassPortFull((-w2, -h2, w2, h2), SAll, Some(c), rd).copy(bp = None)
        // Non-box shapes pass an inside context: the compass point comes
        // from compassPoint (shapes.c:2648) — a degenerate bezier from the
        // centre toward the ray target, trimmed by bezier_clip against the
        // penwidth-inflated inside fn (±0.5pt convergence — the coarse
        // bisection IS the oracle value). Ellipse-family only (a compass on
        // a non-box POLYGON would need its polygon inside fn — no corpus).
        val shapeName = n.attrs.getOrElse("shape", "ellipse").toLowerCase
        val ellipseLike = Set("ellipse", "circle", "oval", "doublecircle", "point").contains(shapeName)
        import Compass.*
        val ray: Option[(Double, Double)] = c match // FINAL-frame ray target
          case E  => Some((1.0, 0.0));  case W  => Some((-1.0, 0.0))
          case N  => Some((0.0, 1.0));  case S  => Some((0.0, -1.0))
          case NE => Some((1.0, 1.0));  case NW => Some((-1.0, 1.0))
          case SE => Some((1.0, -1.0)); case SW => Some((-1.0, -1.0))
          case _  => None
        (if ellipseLike then ray else None) match
          case Some((rx, ry)) =>
            val pw   = n.attrs.get("penwidth").flatMap(_.toDoubleOption).map(math.max(0.0, _)).getOrElse(1.0) // ATTR only
            val urx  = w2 + pw / 2.0
            val ury  = h2 + pw / 2.0
            val maxv = 4.0 * math.max(w2, h2)
            // target: axis rays keep the centred coordinate (compassPort
            // passes ctr for the other axis = 0 in node-local).
            val (tfx, tfy) = (rx * maxv, ry * maxv)
            val (cx0, cy0) = cwrot(tfx, tfy, rd) // final → canonical
            // poly_inside ellipse: canonical query ccw-rotates to the true
            // frame; box test strict >, then hypot < 1 on the outline.
            val inside: Spline.XY => Boolean = p =>
              val (px, py) = ccwrot(p.x, p.y, rd)
              if math.abs(px) > urx || math.abs(py) > ury then false
              else math.hypot(px / urx, py / ury) < 1.0
            val curve = Array(Spline.XY(0, 0), Spline.XY(0, 0), Spline.XY(cx0, cy0), Spline.XY(cx0, cy0))
            Spline.bezierClip(curve, true, inside)
            base.copy(px = curve(0).x, py = curve(0).y) // already canonical
          case None => base
      }
    }

  /** HTML table cell port: `<td port="name">`. The cell box is table-local,
    * y-up, centred on the table — and the table is centred on the node — so it
    * doubles as the node-local field box. */
  private def htmlCellPort(n: RNode, name: String, compass: Option[Compass]): Option[Anchor] =
    import org.jpablo.graphexplorer.graphviz.html.{HtmlParser, HtmlLabel, HtmlTableLayout}
    if !n.attrs.isHtml("label") then None
    else
      HtmlParser.parse(n.attrs.getOrElse("label", "")) match
        case Some(HtmlLabel.Table(tbl)) =>
          // The cell boxes MUST be laid out in the node's own font — a row's
          // height is `fontsize*LINESPACING + 2*cellpadding + 2*cellborder`,
          // so the default 14 stretches every row and walks the ports apart
          // (191 sets fontsize=10: gv's pitch is 22, the 14pt default gives
          // 26.8). `gvHtmlPort` below already reads the node's attrs; this
          // path did not.
          val fs = n.attrs.get("fontsize").flatMap(_.toDoubleOption).getOrElse(14.0)
          val fn = n.attrs.getOrElse("fontname", "Times")
          HtmlTableLayout.cellPortBox(tbl, name, fs, fn)
            .map(b => fromBox(b.llx, b.lly, b.urx, b.ury, compass))
        case _ => None

end PortAnchor
