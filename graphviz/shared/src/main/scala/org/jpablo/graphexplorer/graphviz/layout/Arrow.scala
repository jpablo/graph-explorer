package org.jpablo.graphexplorer.graphviz.layout

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

  val Length = 10.0 // ARROW_LENGTH (× lenfact × arrowsize)

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
  def lengthNormal(penwidth: Double, arrowsize: Double): Double =
    if arrowsize == 0 then 0.0
    else
      val mag = arrowsize * Length // lenfact = 1.0 for ARR_TYPE_NORM
      val (_, _, _, q) = normal0((0.0, 0.0), (mag, 0.0), penwidth)
      // full_length = q.x (arrow points along +x, ends at origin); the
      // non-INV overlap is penwidth/2 (overlap_at_tip is INV-only).
      q._1 - penwidth / 2.0

end Arrow
