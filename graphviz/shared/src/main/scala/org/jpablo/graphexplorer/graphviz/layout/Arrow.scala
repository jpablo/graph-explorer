package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.units.Length.Pt

/** Normal-arrowhead geometry — faithful port of `lib/common/arrows.c`
  * (`arrow_type_normal0` + `miter_shape` + `arrow_length_normal`), gv
  * 13.0.1, for the plain `normal` head (no LEFT/RIGHT/INV/OPEN modifiers —
  * the only arrowhead the corpus exercises; `vee` rides on the LR-deferred
  * 02).
  *
  * Shared by `Spline.clipInstall` (the true arrow *length* to trim the
  * spline by) and `Svg` (the arrowhead polygon). The `delta_tip` miter and
  * the matching arrow length are what closed the long-standing sub-2px
  * M5/M7 residual: a stroked triangle's point extends past its geometric
  * vertex by the line-join apex, so the real arrow is ≈11.53 pt, not the
  * nominal `ARROW_LENGTH` 10.
  */
object Arrow:

  /** `ARROW_LENGTH` (× lenfact × arrowsize). Points-typed at the API
    * boundary; the internal numerical kernel below stays raw-Double for
    * tight tuple arithmetic. */
  val Length: Pt = Pt(10.0)
  private val LengthD: Double = 10.0

  private type P2 = (Double, Double)

  /** `miter_shape`: the stroke line-join triangle apex (`points[0]`) at `p`
    * for segments `bl→p` and `p→br`; SVG `stroke-miterlimit=4` ⇒ bevel
    * (P1/P2 midpoint) fallback when the join is too sharp. */
  def miterShape(bl: P2, p: P2, br: P2, pw: Double): P2 =
    if (bl == p) || (br == p) then p
    else
      val dxA = p._1 - bl._1; val dyA = p._2 - bl._2; val hA = math.hypot(dxA, dyA)
      val cosA = dxA / hA; val sinA = dyA / hA
      val p1   = (p._1 - pw / 2.0 * sinA, p._2 + pw / 2.0 * cosA)
      val dxB  = br._1 - p._1; val dyB = br._2 - p._2; val hB = math.hypot(dxB, dyB)
      val cosB = dxB / hB; val sinB = dyB / hB
      val alpha = if dyA > 0 then math.acos(cosA) else -math.acos(cosA)
      val beta  = if dyB > 0 then math.acos(cosB) else -math.acos(cosB)
      val betaRev = beta - math.Pi
      val theta = betaRev - alpha + (if betaRev - alpha <= -math.Pi then 2 * math.Pi else 0.0)
      val p2 = (p._1 + pw / 2.0 * (-sinB), p._2 - pw / 2.0 * (-cosB))
      if 1.0 / math.sin(theta / 2.0) > 4.0 then
        ((p1._1 + p2._1) / 2.0, (p1._2 + p2._2) / 2.0)
      else
        val l = pw / 2.0 / math.tan(theta / 2.0)
        (p1._1 + l * cosA, p1._2 + l * sinA)

  /** `arrow_type_normal0` (normal head). `p` = arrow tip (`bezier.ep`),
    * `u` = the arrow-length vector pointing back up the edge. Returns the
    * 3 polygon points `a[1..3]` plus the returned spline-attach `q`
    * (`p+u−delta_tip−delta_base`). */
  def normal0(p: P2, u: P2, pw: Double): (P2, P2, P2, P2) =
    val aw = if pw > 4 then 0.35 * pw / 4 else 0.35
    val v  = (-u._2 * aw, u._1 * aw)
    val q0 = (p._1 + u._1, p._2 + u._2)
    val bl = (-v._1, -v._2); val br = v
    val bigP = (-u._1, -u._2)
    val hP   = math.hypot(bigP._1, bigP._2)
    val cosPhi = bigP._1 / hP; val sinPhi = bigP._2 / hP
    val p3   = miterShape(bl, bigP, br, pw)
    val dtip = (p3._1 - bigP._1, p3._2 - bigP._2)
    val pp = (p._1 - dtip._1, p._2 - dtip._2)
    val qq = (q0._1 - dtip._1, q0._2 - dtip._2)
    val a1 = (qq._1 - v._1, qq._2 - v._2)
    val a2 = pp
    val a3 = (qq._1 + v._1, qq._2 + v._2)
    val q  = (qq._1 - pw / 2.0 * cosPhi, qq._2 - pw / 2.0 * sinPhi)
    (a1, a2, a3, q)

  /** `arrow_length_normal`: the spline-trim length for a `normal` head.
    * `full_length − overlap` where `full_length = q.x` of `normal0` along
    * +x and `overlap = penwidth/2` (no INV). ≈11.53 at the defaults. */
  def lengthNormal(penwidth: Double, arrowsize: Double): Pt =
    if arrowsize == 0 then Pt.Zero
    else
      val mag = arrowsize * LengthD // lenfact = 1.0 for ARR_TYPE_NORM
      val (_, _, _, q) = normal0((0.0, 0.0), (mag, 0.0), penwidth)
      // full_length = q.x (arrow points along +x, ends at origin); the
      // non-INV overlap is penwidth/2 (overlap_at_tip is INV-only).
      Pt(q._1 - penwidth / 2.0)

  /** `arrow_type_crow0` for the plain `vee` head (`ARR_MOD_INV`, no L/R —
    * the only crow variant the corpus exercises, on 02). `p` = arrow tip,
    * `u` = the arrow-length vector back up the edge. Returns the 9-point
    * polygon `a[0..8]` and the spline-attach `q` (`p+u−delta_tip−delta_base`).
    * shaftwidth `w` is 0 at penwidth ≤ 1, so a[2..6] collapse toward `q`/`m`. */
  def crow0(p: P2, u: P2, arrowsize: Double, pw: Double): (Array[P2], P2) =
    val aw = if pw > 4 * arrowsize then 0.45 * pw / (4 * arrowsize) else 0.45
    val sw = if pw > 1 then 0.05 * (pw - 1) / arrowsize else 0.0
    val v  = (-u._2 * aw, u._1 * aw)
    val w  = (-u._2 * sw, u._1 * sw)
    var q  = (p._1 + u._1, p._2 + u._2)
    val m  = (p._1 + u._1 * 0.5, p._2 + u._2 * 0.5)
    val bigP = (-u._1, -u._2)                    // inv_tip (INV)
    val bl = (-v._1, -v._2)                      // base_left  = normal_right (INV)
    val br = v                                   // base_right = normal_left  (INV)
    val hP = math.hypot(bigP._1, bigP._2)
    val cosPhi = bigP._1 / hP; val sinPhi = bigP._2 / hP
    val p3   = miterShape(bl, bigP, br, pw)      // else-branch (no L/R): points[0]
    val dtip = (p3._1 - bigP._1, p3._2 - bigP._2)
    val dbase = (pw / 2.0 * cosPhi, pw / 2.0 * sinPhi) // INV
    val pp = (p._1 - dtip._1, p._2 - dtip._2)
    q = (q._1 - dtip._1, q._2 - dtip._2)
    val a = Array[P2](
      pp,
      (q._1 - v._1, q._2 - v._2),
      (m._1 - w._1, m._2 - w._2),
      (q._1 - w._1, q._2 - w._2),
      q,
      (q._1 + w._1, q._2 + w._2),
      (m._1 + w._1, m._2 + w._2),
      (q._1 + v._1, q._2 + v._2),
      pp
    )
    q = (q._1 - dbase._1, q._2 - dbase._2)
    (a, q)

  /** `arrow_length_crow`: spline-trim length for `vee`. `full_length − overlap`,
    * `full_length = q.x` of `crow0` along +x, INV overlap = `penwidth/2`
    * (`overlap_at_base`). ≈11.22 at the defaults (vs normal's 11.53). */
  def lengthCrow(penwidth: Double, arrowsize: Double): Pt =
    if arrowsize == 0 then Pt.Zero
    else
      val mag = arrowsize * LengthD // lenfact = 1.0 for ARR_TYPE_CROW
      val (_, q) = crow0((0.0, 0.0), (mag, 0.0), arrowsize, penwidth)
      Pt(q._1 - penwidth / 2.0)

  /** The head-arrow trim length for an edge's `arrowhead` attribute. Only
    * `normal` (default) and `vee` are modelled; everything else falls back to
    * normal (no corpus exercises the others yet). */
  def length(arrowhead: String, penwidth: Double, arrowsize: Double): Pt =
    if arrowhead == "vee" then lengthCrow(penwidth, arrowsize)
    else lengthNormal(penwidth, arrowsize)

end Arrow
