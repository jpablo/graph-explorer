package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.units.Length.Pt

/** Arrowhead geometry — faithful port of `lib/common/arrows.c`, gv 13.0.1.
  *
  * Two things live here, and they have to agree or the drawing tears: the
  * shapes an arrow draws ([[gen]], used by `Svg`) and the length the spline is
  * trimmed by to make room for them ([[arrowLength]], used by
  * `Spline.clipInstall`). Both are driven by the same packed flag word, so an
  * arrow that draws as a dot is also clipped as a dot.
  *
  * `delta_tip`/`delta_base` are why the trim lengths are not the nominal
  * `ARROW_LENGTH` 10: a *stroked* polygon extends past its geometric vertex by
  * the line-join apex, so a `normal` head is really ≈11.53 pt long. Closing
  * that gap is what removed the long-standing M5/M7 sub-2px residual.
  */
object Arrow:

  /** `ARROW_LENGTH` — the nominal length every type scales from. */
  val Length: Pt = Pt(10.0)
  private val LengthD: Double = 10.0

  type P2 = (Double, Double)

  // ── the packed flag word (arrows.c:30) ──────────────────────────────────
  // Up to NumbOfArrowHeads shapes stacked on one end ("odotnormal"), 8 bits
  // each: the low 4 a type, the high 4 the o/l/r/inv modifiers.
  private val BitsPerArrow     = 8
  private val BitsPerArrowType = 4
  val NumbOfArrowHeads         = 4
  private val TypeMask         = (1 << BitsPerArrowType) - 1
  private val ArrowMask        = (1 << BitsPerArrow) - 1

  val TypeNone    = 0
  val TypeNorm    = 1
  val TypeCrow    = 2
  val TypeTee     = 3
  val TypeBox     = 4
  val TypeDiamond = 5
  val TypeDot     = 6
  val TypeCurve   = 7
  val TypeGap     = 8

  val ModOpen  = 1 << (BitsPerArrowType + 0)
  val ModInv   = 1 << (BitsPerArrowType + 1)
  val ModLeft  = 1 << (BitsPerArrowType + 2)
  val ModRight = 1 << (BitsPerArrowType + 3)

  extension (flag: Int)
    private inline def has(mod: Int): Boolean = (flag & mod) != 0

  /** `Arrowtypes[].lenfact` — this type's length as a ratio of the standard. */
  private def lenfact(tpe: Int): Double = tpe match
    case TypeTee     => 0.5
    case TypeDiamond => 1.2
    case TypeDot     => 0.8
    case TypeGap     => 0.5
    case _           => 1.0

  // ── name → flags (arrows.c:159) ─────────────────────────────────────────

  /** Evaluated before the primary names, else "invempty" would parse as
    * `inv` + a leftover "empty". */
  private val Arrowsynonyms = List("invempty" -> (TypeNorm | ModInv | ModOpen))

  private val Arrowmods = List(
    "o"    -> ModOpen,
    "r"    -> ModRight,
    "l"    -> ModLeft,
    // deprecated alternates: "e" for "ediamond", "half" for "halfopen"
    "e"    -> ModOpen,
    "half" -> ModLeft
  )

  private val Arrownames = List(
    "normal"  -> TypeNorm,
    "crow"    -> TypeCrow,
    "tee"     -> TypeTee,
    "box"     -> TypeBox,
    "diamond" -> TypeDiamond,
    "dot"     -> TypeDot,
    "none"    -> TypeGap,
    // ModInv appears here only, to define two extra shapes — not every type
    // can use it.
    "inv"     -> (TypeNorm | ModInv),
    "vee"     -> (TypeCrow | ModInv),
    // gv's own kludges: "o" and "e" are already taken as modifiers, so "open"
    // is matched as "pen" and "empty" as "mpty" after the mod pass has eaten
    // the first letter.
    "pen"     -> (TypeCrow | ModInv),
    "mpty"    -> TypeNorm,
    "curve"   -> TypeCurve,
    "icurve"  -> (TypeCurve | ModInv)
  )

  /** `arrow_match_name_frag`: consume the first table entry `name` starts
    * with, or leave `name` untouched. */
  private def matchNameFrag(name: String, table: List[(String, Int)], flag: Int): (String, Int) =
    table.find((n, _) => name.startsWith(n)) match
      case Some((n, t)) => (name.substring(n.length), flag | t)
      case None         => (name, flag)

  /** `arrow_match_shape`: one shape = any number of modifiers then a name. */
  private def matchShape(name: String): (String, Int) =
    var (rest, f) = matchNameFrag(name, Arrowsynonyms, TypeNone)
    if rest == name then
      var progressed = true
      while progressed do
        val (r, nf) = matchNameFrag(rest, Arrowmods, f)
        progressed = r != rest
        rest = r; f = nf
      val (r, nf) = matchNameFrag(rest, Arrownames, f)
      rest = r; f = nf
    // modifiers with no name of their own ("o") describe a normal arrow
    if f != 0 && (f & TypeMask) == 0 then f |= TypeNorm
    (rest, f)

  /** `arrow_match_name`: a whole `arrowhead`/`arrowtail` value → packed flags.
    * An unknown fragment stops the parse (gv warns and keeps what it had). */
  def matchName(name: String): Int =
    var flag = 0
    var rest = name
    var i    = 0
    var stop = false
    while !stop && rest.nonEmpty && i < NumbOfArrowHeads do
      val (r, f0) = matchShape(rest)
      rest = r
      var f = f0
      if f == TypeNone then stop = true
      else
        // a `none` that would be the last slot, or the only one, is not a gap
        // at all — it means "no arrow here"
        if f == TypeGap && i == NumbOfArrowHeads - 1 then f = TypeNone
        if f == TypeGap && i == 0 && rest.isEmpty then f = TypeNone
        if f != TypeNone then
          flag |= f << (i * BitsPerArrow)
          i += 1
    flag

  /** `arrow_flags` (arrows.c:217): which ends carry arrows, and as what.
    * `dir` sets each end's base flag; `arrowhead`/`arrowtail` refine an end
    * ONLY while it is still a plain normal — which is why `dir=back` ignores
    * `arrowhead` entirely. Returns (tail flags, head flags); 0 = no arrow. */
  def flags(
      directed:  Boolean,
      dir:       Option[String],
      arrowhead: Option[String],
      arrowtail: Option[String]
  ): (Int, Int) =
    var sflag = TypeNone
    var eflag = if directed then TypeNorm else TypeNone
    dir.filter(_.nonEmpty).foreach {
      case "forward" => sflag = TypeNone; eflag = TypeNorm
      case "back"    => sflag = TypeNorm; eflag = TypeNone
      case "both"    => sflag = TypeNorm; eflag = TypeNorm
      case "none"    => sflag = TypeNone; eflag = TypeNone
      case _         => () // unknown dir: gv leaves the flags alone
    }
    if eflag == TypeNorm then arrowhead.filter(_.nonEmpty).foreach(a => eflag = matchName(a))
    if sflag == TypeNorm then arrowtail.filter(_.nonEmpty).foreach(a => sflag = matchName(a))
    (sflag, eflag)

  // ── stroke geometry ─────────────────────────────────────────────────────

  /** `miter_shape`: the stroke line-join shape at `P` for the segments
    * `base_left→P` and `P→base_right`, as (P3, P1, P2). SVG's
    * `stroke-miterlimit=4` means a sharp enough join bevels instead, and then
    * P3 is the P1/P2 midpoint. */
  def miterShape(baseLeft: P2, bigP: P2, baseRight: P2, penwidth: Double): (P2, P2, P2) =
    if baseLeft == bigP || baseRight == bigP then
      // the stroke shape is really a point; SVG renderers do not extend it
      (bigP, bigP, bigP)
    else
      val dxA  = bigP._1 - baseLeft._1; val dyA = bigP._2 - baseLeft._2
      val hA   = math.hypot(dxA, dyA)
      val cosA = dxA / hA; val sinA = dyA / hA
      val p1   = (bigP._1 - penwidth / 2.0 * sinA, bigP._2 + penwidth / 2.0 * cosA)
      val dxB  = baseRight._1 - bigP._1; val dyB = baseRight._2 - bigP._2
      val hB   = math.hypot(dxB, dyB)
      val cosB = dxB / hB; val sinB = dyB / hB
      val alpha   = if dyA > 0 then math.acos(cosA) else -math.acos(cosA)
      val beta    = if dyB > 0 then math.acos(cosB) else -math.acos(cosB)
      val betaRev = beta - math.Pi
      val theta   = betaRev - alpha + (if betaRev - alpha <= -math.Pi then 2 * math.Pi else 0.0)
      val p2      = (bigP._1 + penwidth / 2.0 * (-sinB), bigP._2 - penwidth / 2.0 * (-cosB))
      if 1.0 / math.sin(theta / 2.0) > 4.0 then
        (((p1._1 + p2._1) / 2.0, (p1._2 + p2._2) / 2.0), p1, p2)
      else
        val l = penwidth / 2.0 / math.tan(theta / 2.0)
        ((p1._1 + l * cosA, p1._2 + l * sinA), p1, p2)

  /** The `delta_tip` an `l`/`r` half-arrow gets: the join point projected back
    * onto the arrow's own axis. Shared verbatim by normal0 and crow0. */
  private def deltaTipFromJoin(join: P2, bigP: P2, cosPhi: Double, sinPhi: Double, phi: Double): P2 =
    val dx    = join._1 - bigP._1
    val dy    = join._2 - bigP._2
    val h     = math.hypot(dx, dy)
    val alpha = if dy > 0 then math.acos(dx / h) else -math.acos(dx / h)
    val len   = h * math.cos(alpha - phi)
    (len * cosPhi, len * sinPhi)

  // ── generators (arrows.c:521) ───────────────────────────────────────────

  /** What a generator draws, in gv's `gvrender_*` terms. Keeping the shapes as
    * data — rather than letting the geometry write SVG — is what lets these
    * be checked against the oracle on the JVM. */
  enum Prim:
    case Polygon(pts: Array[P2], filled: Boolean)
    case Polyline(pts: Array[P2])
    case Ellipse(center: P2, r: Double, filled: Boolean)
    case Curve(pts: Array[P2])

  /** `arrow_type_normal0`. `p` = arrow tip, `u` = the arrow-length vector back
    * up the edge. Returns `a[0..4]` and the spline-attach point. */
  def normal0(p0: P2, u: P2, penwidth: Double, flag: Int): (Array[P2], P2) =
    var p = p0
    val arrowwidth = if penwidth > 4 then 0.35 * penwidth / 4 else 0.35
    val v = (-u._2 * arrowwidth, u._1 * arrowwidth)
    var q = (p._1 + u._1, p._2 + u._2)

    var deltaBase: P2 = (0.0, 0.0)
    val origin: P2    = (0.0, 0.0)
    val vInv: P2      = (-v._1, -v._2)
    val normalLeft    = if flag.has(ModRight) then origin else vInv
    val normalRight   = if flag.has(ModLeft) then origin else v
    val baseLeft      = if flag.has(ModInv) then normalRight else normalLeft
    val baseRight     = if flag.has(ModInv) then normalLeft else normalRight
    val bigP: P2      = if flag.has(ModInv) then u else (-u._1, -u._2)

    var deltaTip: P2 = (0.0, 0.0)
    if u._1 != 0 || u._2 != 0 then
      val h      = math.hypot(bigP._1, bigP._2)
      val cosPhi = bigP._1 / h
      val sinPhi = bigP._2 / h
      val phi    = if bigP._2 > 0 then math.acos(cosPhi) else -math.acos(cosPhi)
      val (p3, p1, p2) = miterShape(baseLeft, bigP, baseRight, penwidth)
      deltaTip =
        if flag.has(ModLeft) then deltaTipFromJoin(p1, bigP, cosPhi, sinPhi, phi)
        else if flag.has(ModRight) then deltaTipFromJoin(p2, bigP, cosPhi, sinPhi, phi)
        else (p3._1 - bigP._1, p3._2 - bigP._2)
      deltaBase = (penwidth / 2.0 * cosPhi, penwidth / 2.0 * sinPhi)

    val a = new Array[P2](5)
    if flag.has(ModInv) then
      p = (p._1 + deltaBase._1, p._2 + deltaBase._2)
      q = (q._1 + deltaBase._1, q._2 + deltaBase._2)
      a(0) = p; a(4) = p
      a(1) = (p._1 - v._1, p._2 - v._2)
      a(2) = q
      a(3) = (p._1 + v._1, p._2 + v._2)
      q = (q._1 + deltaTip._1, q._2 + deltaTip._2)
    else
      p = (p._1 - deltaTip._1, p._2 - deltaTip._2)
      q = (q._1 - deltaTip._1, q._2 - deltaTip._2)
      a(0) = q; a(4) = q
      a(1) = (q._1 - v._1, q._2 - v._2)
      a(2) = p
      a(3) = (q._1 + v._1, q._2 + v._2)
      q = (q._1 - deltaBase._1, q._2 - deltaBase._2)
    (a, q)

  private def typeNormal(p: P2, u: P2, penwidth: Double, flag: Int): (List[Prim], P2) =
    val (a, q) = normal0(p, u, penwidth, flag)
    val filled = !flag.has(ModOpen)
    val poly =
      if flag.has(ModLeft) then Prim.Polygon(a.slice(0, 3), filled)
      else if flag.has(ModRight) then Prim.Polygon(a.slice(2, 5), filled)
      else Prim.Polygon(a.slice(1, 4), filled)
    (List(poly), q)

  /** `arrow_type_crow0`. The `vee` (INV) and `crow` (plain) shapes share this;
    * they differ in which end is the tip and how the base is pulled back —
    * a crow's base extension comes from its "toe" join, not from penwidth. */
  def crow0(p0: P2, u: P2, arrowsize: Double, penwidth: Double, flag: Int): (Array[P2], P2) =
    var p = p0
    val inv = flag.has(ModInv)
    val arrowwidth = if penwidth > 4 * arrowsize && inv then 0.45 * penwidth / (4 * arrowsize) else 0.45
    val shaftwidth = if penwidth > 1 && inv then 0.05 * (penwidth - 1) / arrowsize else 0.0

    val v = (-u._2 * arrowwidth, u._1 * arrowwidth)
    val w = (-u._2 * shaftwidth, u._1 * shaftwidth)
    var q = (p._1 + u._1, p._2 + u._2)
    val m = (p._1 + u._1 * 0.5, p._2 + u._2 * 0.5)

    var deltaBase: P2 = (0.0, 0.0)
    val origin: P2    = (0.0, 0.0)
    val vInv: P2      = (-v._1, -v._2)
    val normalLeft    = if flag.has(ModRight) then origin else v
    val normalRight   = if flag.has(ModLeft) then origin else vInv
    val baseLeft      = if inv then normalRight else normalLeft
    val baseRight     = if inv then normalLeft else normalRight
    val bigP: P2      = if inv then (-u._1, -u._2) else u

    var deltaTip: P2 = (0.0, 0.0)
    if u._1 != 0 || u._2 != 0 then
      val h      = math.hypot(bigP._1, bigP._2)
      val cosPhi = bigP._1 / h
      val sinPhi = bigP._2 / h
      val phi    = if bigP._2 > 0 then math.acos(cosPhi) else -math.acos(cosPhi)
      val (p3, p1, p2) = miterShape(baseLeft, bigP, baseRight, penwidth)
      deltaTip =
        if (flag.has(ModLeft) && inv) || (flag.has(ModRight) && !inv) then
          deltaTipFromJoin(p2, bigP, cosPhi, sinPhi, phi)
        else if (flag.has(ModLeft) && !inv) || (flag.has(ModRight) && inv) then
          deltaTipFromJoin(p1, bigP, cosPhi, sinPhi, phi)
        else (p3._1 - bigP._1, p3._2 - bigP._2)
      if inv then deltaBase = (penwidth / 2.0 * cosPhi, penwidth / 2.0 * sinPhi)
      else
        // The crow's two "toes" extend forward by the same amount whatever the
        // l/r modifier says, so the right toe alone gives the extension.
        val toeBaseLeft  = (m._1 - q._1 + w._1, m._2 - q._2 + w._2)
        val toeBaseRight = origin
        val toeP: P2     = (v._1 - u._1, v._2 - u._2)
        val (_, tp1, _)  = miterShape(toeBaseLeft, toeP, toeBaseRight, penwidth)
        val dx    = tp1._1 - toeP._1
        val dy    = tp1._2 - toeP._2
        val hh    = math.hypot(dx, dy)
        val alpha = if dy > 0 then math.acos(dx / hh) else -math.acos(dx / hh)
        val len   = -hh * math.cos(alpha - phi)
        deltaBase = (len * cosPhi, len * sinPhi)

    val a = new Array[P2](9)
    if inv then // vee
      p = (p._1 - deltaTip._1, p._2 - deltaTip._2)
      q = (q._1 - deltaTip._1, q._2 - deltaTip._2)
      a(0) = p; a(8) = p
      a(1) = (q._1 - v._1, q._2 - v._2)
      a(2) = (m._1 - w._1, m._2 - w._2)
      a(3) = (q._1 - w._1, q._2 - w._2)
      a(4) = q
      a(5) = (q._1 + w._1, q._2 + w._2)
      a(6) = (m._1 + w._1, m._2 + w._2)
      a(7) = (q._1 + v._1, q._2 + v._2)
      q = (q._1 - deltaBase._1, q._2 - deltaBase._2)
    else // crow
      p = (p._1 + deltaBase._1, p._2 + deltaBase._2)
      q = (q._1 + deltaBase._1, q._2 + deltaBase._2)
      a(0) = q; a(8) = q
      a(1) = (p._1 - v._1, p._2 - v._2)
      a(2) = (m._1 - w._1, m._2 - w._2)
      a(3) = (p._1 + deltaBase._1, p._2 + deltaBase._2)
      a(4) = (p._1 + deltaBase._1, p._2 + deltaBase._2)
      a(5) = (p._1 + deltaBase._1, p._2 + deltaBase._2)
      a(6) = (m._1 + w._1, m._2 + w._2)
      a(7) = (p._1 + v._1, p._2 + v._2)
      q = (q._1 + deltaTip._1, q._2 + deltaTip._2)
    (a, q)

  private def typeCrow(p: P2, u: P2, arrowsize: Double, penwidth: Double, flag: Int): (List[Prim], P2) =
    val (a, q) = crow0(p, u, arrowsize, penwidth, flag)
    val poly =
      if flag.has(ModLeft) then Prim.Polygon(a.slice(0, 5), true)
      else if flag.has(ModRight) then Prim.Polygon(a.slice(4, 9), true)
      else Prim.Polygon(a.slice(0, 8), true)
    (List(poly), q)

  /** `arrow_type_gap`: the spacer between two stacked heads. Draws the line it
    * occupies, so a gap between shapes is not a hole in the edge. */
  private def typeGap(p: P2, u: P2): (List[Prim], P2) =
    val q = (p._1 + u._1, p._2 + u._2)
    (List(Prim.Polyline(Array(p, q))), q)

  /** `arrow_type_tee`: a crossbar on a stem. */
  private def typeTee(p0: P2, u: P2, penwidth: Double, flag: Int): (List[Prim], P2) =
    var p = p0
    val v = (-u._2, u._1)
    var q = (p._1 + u._1, p._2 + u._2)
    var m = (p._1 + u._1 * 0.2, p._2 + u._2 * 0.2)
    var n = (p._1 + u._1 * 0.6, p._2 + u._2 * 0.6)

    val length = math.hypot(u._1, u._2)
    val polygonExtendOverPolyline = penwidth / 2 - 0.2 * length
    if length > 0 && polygonExtendOverPolyline > 0 then
      // a fat crossbar would otherwise reach past the stem and into the node
      val bigP: P2 = (-u._1, -u._2)
      val h        = math.hypot(bigP._1, bigP._2)
      val delta    = (polygonExtendOverPolyline * (bigP._1 / h), polygonExtendOverPolyline * (bigP._2 / h))
      p = (p._1 - delta._1, p._2 - delta._2)
      m = (m._1 - delta._1, m._2 - delta._2)
      n = (n._1 - delta._1, n._2 - delta._2)
      q = (q._1 - delta._1, q._2 - delta._2)

    val a = Array[P2](
      (m._1 + v._1, m._2 + v._2),
      (m._1 - v._1, m._2 - v._2),
      (n._1 - v._1, n._2 - v._2),
      (n._1 + v._1, n._2 + v._2)
    )
    if flag.has(ModLeft) then { a(0) = m; a(3) = n }
    else if flag.has(ModRight) then { a(1) = m; a(2) = n }
    // A polyline does not extend visually beyond its start, so `q` is returned
    // as-is, without a penwidth term.
    (List(Prim.Polygon(a, true), Prim.Polyline(Array(p, q))), q)

  /** `arrow_type_box`: a rectangle on a stem. */
  private def typeBox(p0: P2, u: P2, penwidth: Double, flag: Int): (List[Prim], P2) =
    var p = p0
    val v = (-u._2 * 0.4, u._1 * 0.4)
    var m = (p._1 + u._1 * 0.8, p._2 + u._2 * 0.8)
    var q = (p._1 + u._1, p._2 + u._2)

    var delta: P2 = (0.0, 0.0)
    if u._1 != 0 || u._2 != 0 then
      val bigP: P2 = (-u._1, -u._2)
      val h        = math.hypot(bigP._1, bigP._2)
      delta = (penwidth / 2.0 * (bigP._1 / h), penwidth / 2.0 * (bigP._2 / h))

    // move the arrow back so it does not visually overlap the node
    p = (p._1 - delta._1, p._2 - delta._2)
    m = (m._1 - delta._1, m._2 - delta._2)
    q = (q._1 - delta._1, q._2 - delta._2)

    val a = Array[P2](
      (p._1 + v._1, p._2 + v._2),
      (p._1 - v._1, p._2 - v._2),
      (m._1 - v._1, m._2 - v._2),
      (m._1 + v._1, m._2 + v._2)
    )
    if flag.has(ModLeft) then { a(0) = p; a(3) = m }
    else if flag.has(ModRight) then { a(1) = p; a(2) = m }
    (List(Prim.Polygon(a, !flag.has(ModOpen)), Prim.Polyline(Array(m, q))), q)

  /** `arrow_type_diamond0`. */
  def diamond0(p0: P2, u: P2, penwidth: Double, flag: Int): (Array[P2], P2) =
    var p = p0
    val v = (-u._2 / 3.0, u._1 / 3.0)
    var r = (p._1 + u._1 / 2.0, p._2 + u._2 / 2.0)
    var q = (p._1 + u._1, p._2 + u._2)

    val origin: P2   = (0.0, 0.0)
    val unmodLeft    = (-0.5 * u._1 - v._1, -0.5 * u._2 - v._2)
    val unmodRight   = (-0.5 * u._1 + v._1, -0.5 * u._2 + v._2)
    val baseLeft     = if flag.has(ModRight) then origin else unmodLeft
    val baseRight    = if flag.has(ModLeft) then origin else unmodRight
    val bigP: P2     = (-u._1, -u._2)
    val (p3, _, _)   = miterShape(baseLeft, bigP, baseRight, penwidth)
    val delta        = (p3._1 - bigP._1, p3._2 - bigP._2)

    p = (p._1 - delta._1, p._2 - delta._2)
    r = (r._1 - delta._1, r._2 - delta._2)
    q = (q._1 - delta._1, q._2 - delta._2)

    val a = Array[P2](q, (r._1 + v._1, r._2 + v._2), p, (r._1 - v._1, r._2 - v._2), q)
    (a, (q._1 - delta._1, q._2 - delta._2))

  private def typeDiamond(p: P2, u: P2, penwidth: Double, flag: Int): (List[Prim], P2) =
    val (a, q) = diamond0(p, u, penwidth, flag)
    val filled = !flag.has(ModOpen)
    val poly =
      if flag.has(ModLeft) then Prim.Polygon(a.slice(2, 5), filled)
      else if flag.has(ModRight) then Prim.Polygon(a.slice(0, 3), filled)
      else Prim.Polygon(a.slice(0, 4), filled)
    (List(poly), q)

  /** `arrow_type_dot`: a circle whose diameter is the arrow length. */
  private def typeDot(p0: P2, u: P2, penwidth: Double, flag: Int): (List[Prim], P2) =
    var p = p0
    val r = math.hypot(u._1, u._2) / 2.0

    var delta: P2 = (0.0, 0.0)
    if u._1 != 0 || u._2 != 0 then
      val bigP: P2 = (-u._1, -u._2)
      val h        = math.hypot(bigP._1, bigP._2)
      delta = (penwidth / 2.0 * (bigP._1 / h), penwidth / 2.0 * (bigP._2 / h))
      p = (p._1 - delta._1, p._2 - delta._2)

    val center = (p._1 + u._1 / 2.0, p._2 + u._2 / 2.0)
    val q      = (p._1 + u._1 - delta._1, p._2 + u._2 - delta._2)
    (List(Prim.Ellipse(center, r, !flag.has(ModOpen))), q)

  /** `arrow_type_curve`: a concave semicircle drawn as one cubic bezier that
    * touches `p` at its midpoint. */
  private def typeCurve(p0: P2, u: P2, penwidth: Double, flag: Int): (List[Prim], P2) =
    var p = p0
    val a0 = p
    if !flag.has(ModInv) && (u._1 != 0 || u._2 != 0) then
      val bigP: P2 = (-u._1, -u._2)
      val h        = math.hypot(bigP._1, bigP._2)
      val delta    = (penwidth / 2.0 * (bigP._1 / h), penwidth / 2.0 * (bigP._2 / h))
      p = (p._1 - delta._1, p._2 - delta._2)

    val arrowwidth = if penwidth > 4 then 0.5 * penwidth / 4 else 0.5
    val q = (p._1 + u._1, p._2 + u._2)
    val v = (-u._2 * arrowwidth, u._1 * arrowwidth)
    val w = (v._2, -v._1) // same direction as u, same magnitude as v

    val af = new Array[P2](4)
    af(0) = (p._1 + v._1 + w._1, p._2 + v._2 + w._2)
    af(3) = (p._1 - v._1 + w._1, p._2 - v._2 + w._2)
    if flag.has(ModInv) then // ----(-|
      af(1) = (p._1 + 0.95 * v._1 + w._1 + w._1 * 4.0 / 3.0, af(0)._2 + w._2 * 4.0 / 3.0)
      af(2) = (p._1 - 0.95 * v._1 + w._1 + w._1 * 4.0 / 3.0, af(3)._2 + w._2 * 4.0 / 3.0)
    else // ----)-|
      af(1) = (p._1 + 0.95 * v._1 + w._1 - w._1 * 4.0 / 3.0, af(0)._2 - w._2 * 4.0 / 3.0)
      af(2) = (p._1 - 0.95 * v._1 + w._1 - w._1 * 4.0 / 3.0, af(3)._2 - w._2 * 4.0 / 3.0)

    val curve =
      if flag.has(ModLeft) then bezierSplit(af)._2
      else if flag.has(ModRight) then bezierSplit(af)._1
      else af
    (List(Prim.Polyline(Array(a0, q)), Prim.Curve(curve)), q)

  /** `Bezier(v, 0.5, left, right)` — de Casteljau at the midpoint. */
  private def bezierSplit(v: Array[P2]): (Array[P2], Array[P2]) =
    val tri = Array.ofDim[P2](4, 4)
    var j   = 0
    while j <= 3 do { tri(0)(j) = v(j); j += 1 }
    var i = 1
    while i <= 3 do
      j = 0
      while j <= 3 - i do
        tri(i)(j) = (
          0.5 * tri(i - 1)(j)._1 + 0.5 * tri(i - 1)(j + 1)._1,
          0.5 * tri(i - 1)(j)._2 + 0.5 * tri(i - 1)(j + 1)._2
        )
        j += 1
      i += 1
    (Array(tri(0)(0), tri(1)(0), tri(2)(0), tri(3)(0)),
     Array(tri(3)(0), tri(2)(1), tri(1)(2), tri(0)(3)))

  /** `arrow_gen_type`: scale the unit-length arrow vector by this type's
    * lenfact and arrowsize, then draw it. */
  private def genType(flag: Int, p: P2, u0: P2, arrowsize: Double, penwidth: Double): (List[Prim], P2) =
    val tpe = flag & TypeMask
    if tpe == TypeNone then (Nil, p)
    else
      val f = lenfact(tpe) * arrowsize
      val u = (u0._1 * f, u0._2 * f)
      tpe match
        case TypeNorm    => typeNormal(p, u, penwidth, flag)
        case TypeCrow    => typeCrow(p, u, arrowsize, penwidth, flag)
        case TypeTee     => typeTee(p, u, penwidth, flag)
        case TypeBox     => typeBox(p, u, penwidth, flag)
        case TypeDiamond => typeDiamond(p, u, penwidth, flag)
        case TypeDot     => typeDot(p, u, penwidth, flag)
        case TypeCurve   => typeCurve(p, u, penwidth, flag)
        case TypeGap     => typeGap(p, u)
        case _           => (Nil, p)

  /** EPSILON — keeps the arrow vector stable as its length approaches 0. The
    * nudge moves polygon corners by ~1e-4 pt, which is invisible except when a
    * corner lands exactly on a `%.2f` print boundary. */
  private val Epsilon = 0.0001

  /** `arrow_gen`: draw every shape stacked on one end, each starting where the
    * previous one ended. `p` is the arrow tip, `uEnd` the point back up the
    * edge that gives the direction. */
  def gen(flag: Int, p0: P2, uEnd: P2, arrowsize: Double, penwidth: Double): List[Prim] =
    val dx = uEnd._1 - p0._1
    val dy = uEnd._2 - p0._2
    val s  = LengthD / (math.hypot(dx, dy) + Epsilon)
    val u  = ((dx + (if dx >= 0.0 then Epsilon else -Epsilon)) * s,
              (dy + (if dy >= 0.0 then Epsilon else -Epsilon)) * s)
    val out  = List.newBuilder[Prim]
    var p    = p0
    var i    = 0
    var stop = false
    while !stop && i < NumbOfArrowHeads do
      val f = (flag >> (i * BitsPerArrow)) & ArrowMask
      if f == TypeNone then stop = true
      else
        val (prims, np) = genType(f, p, u, arrowsize, penwidth)
        out ++= prims
        p = np
        i += 1
    out.result()

  // ── trim lengths (arrows.c:1187) ────────────────────────────────────────

  private def lengthGeneric(lf: Double, arrowsize: Double): Double =
    lf * arrowsize * LengthD

  /** `arrow_length_normal`. The overlap is at the base normally and at the tip
    * when inverted — an `inv` head points the other way, so the end that meets
    * the edge is the pointy one. */
  private def lengthNormal(lf: Double, arrowsize: Double, penwidth: Double, flag: Int): Double =
    val (a, q)           = normal0((0.0, 0.0), (lf * arrowsize * LengthD, 0.0), penwidth, flag)
    val base1            = a(1); val base2 = a(3); val tip = a(2)
    val fullLength       = q._1
    val nominalLength    = math.abs(base1._1 - tip._1)
    val nominalBaseWidth = base2._2 - base1._2
    val fullBaseWidth    = nominalBaseWidth * fullLength / nominalLength
    val overlapAtBase    = penwidth / 2
    val overlapAtTip     = fullLength * penwidth / fullBaseWidth
    fullLength - (if flag.has(ModInv) then overlapAtTip else overlapAtBase)

  /** `arrow_length_tee`. Transcribed with gv's own copy-paste slip intact: the
    * end-extension term is gated on the START condition. Diverging here would
    * shift the spline trim away from what gv draws. */
  private def lengthTee(lf: Double, arrowsize: Double, penwidth: Double): Double =
    val nominalLength = lf * arrowsize * LengthD
    var length        = nominalLength
    val atStart       = penwidth / 2 - (1 - 0.6) * nominalLength
    if atStart > 0 then length += atStart
    val atEnd = penwidth / 2 - 0.2 * nominalLength
    if atStart > 0 then length += atEnd
    length

  private def lengthBox(lf: Double, arrowsize: Double, penwidth: Double): Double =
    lf * arrowsize * LengthD + penwidth / 2

  private def lengthDiamond(lf: Double, arrowsize: Double, penwidth: Double, flag: Int): Double =
    val (a, q)           = diamond0((0.0, 0.0), (lf * arrowsize * LengthD, 0.0), penwidth, flag)
    val base1            = a(3); val base2 = a(1); val tip = a(2)
    val fullLength       = q._1 / 2
    val nominalLength    = math.abs(base1._1 - tip._1)
    val nominalBaseWidth = base2._2 - base1._2
    val fullBaseWidth    = nominalBaseWidth * fullLength / nominalLength
    2 * fullLength - fullLength * penwidth / fullBaseWidth

  private def lengthDot(lf: Double, arrowsize: Double, penwidth: Double): Double =
    lf * arrowsize * LengthD + penwidth

  private def lengthCurve(lf: Double, arrowsize: Double, penwidth: Double): Double =
    lf * arrowsize * LengthD + penwidth / 2

  /** `arrow_length_crow`. The shaft is excluded from the width scaling — only
    * the head's own taper decides how far the edge may overlap it. */
  private def lengthCrow(lf: Double, arrowsize: Double, penwidth: Double, flag: Int): Double =
    val (a, q) = crow0((0.0, 0.0), (lf * arrowsize * LengthD, 0.0), arrowsize, penwidth, flag)
    val base1  = a(1); val base2 = a(7); val tip = a(0); val shaft1 = a(3)
    val fullLength            = q._1
    val fullLengthWithoutShaft = fullLength - (base1._1 - shaft1._1)
    val nominalLength         = math.abs(base1._1 - tip._1)
    val nominalBaseWidth      = base2._2 - base1._2
    val fullBaseWidth         = nominalBaseWidth * fullLengthWithoutShaft / nominalLength
    val overlapAtBase         = penwidth / 2
    val overlapAtTip          = fullLengthWithoutShaft * penwidth / fullBaseWidth
    fullLength - (if flag.has(ModInv) then overlapAtBase else overlapAtTip)

  /** `arrow_length`: how far to trim the spline for one end, summed over every
    * shape stacked there. */
  def arrowLength(flag: Int, penwidth: Double, arrowsize: Double): Pt =
    if arrowsize == 0 then Pt.Zero
    else
      var length = 0.0
      var i      = 0
      while i < NumbOfArrowHeads do
        val f   = (flag >> (i * BitsPerArrow)) & ArrowMask
        val tpe = f & TypeMask
        val lf  = lenfact(tpe)
        length += (tpe match
          case TypeNorm    => lengthNormal(lf, arrowsize, penwidth, f)
          case TypeCrow    => lengthCrow(lf, arrowsize, penwidth, f)
          case TypeTee     => lengthTee(lf, arrowsize, penwidth)
          case TypeBox     => lengthBox(lf, arrowsize, penwidth)
          case TypeDiamond => lengthDiamond(lf, arrowsize, penwidth, f)
          case TypeDot     => lengthDot(lf, arrowsize, penwidth)
          case TypeCurve   => lengthCurve(lf, arrowsize, penwidth)
          case TypeGap     => lengthGeneric(lf, arrowsize)
          case _           => 0.0
        )
        i += 1
      Pt(length)

end Arrow
