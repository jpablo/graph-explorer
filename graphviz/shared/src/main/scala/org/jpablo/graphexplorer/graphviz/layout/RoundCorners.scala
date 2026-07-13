package org.jpablo.graphexplorer.graphviz.layout

/** Faithful 1:1 transcription of Graphviz `round_corners` +
  * `alloc_interpolation_points` (lib/common/shapes.c, gv 13.0.1) — the
  * special-corner outline generator shared by every `option.shape != 0` node
  * shape: the container shapes (note/tab/folder/box3d/component) and the full
  * SBOL biological-circuit set (promoter … lpromoter). Also `diagonals_draw`
  * (the M-variants) via [[diagonals]].
  *
  * Operates on the node-local, y-up box corners `AF` (poly_init vertex order:
  * AF0=top-right, AF1=top-left, AF2=bottom-left, AF3=bottom-right) and returns
  * a list of draw ops in the same frame; `Svg` translates each point by the
  * node centre and negates y at emit time (exactly as `poly_gencode` hands `AF`
  * — already `ND_coord`-translated — to `gvrender_polygon`). The index
  * arithmetic on the interpolation array `B` is preserved verbatim so the
  * emitted vertices are byte-identical to gv's.
  */
object RoundCorners:

  type P = (Double, Double)

  enum Op:
    case Poly(pts: Vector[P], filled: Boolean)
    case Line(pts: Vector[P])

  /** const.h shape codes; also the set of names routed through here. */
  val codeOf: Map[String, Int] = Map(
    "note" -> 1, "tab" -> 2, "folder" -> 3, "box3d" -> 4, "component" -> 5,
    "promoter" -> 6, "cds" -> 7, "terminator" -> 8, "utr" -> 9, "primersite" -> 10,
    "restrictionsite" -> 11, "fivepoverhang" -> 12, "threepoverhang" -> 13,
    "noverhang" -> 14, "assembly" -> 15, "signature" -> 16, "insulator" -> 17,
    "ribosite" -> 18, "rnastab" -> 19, "proteasesite" -> 20, "proteinstab" -> 21,
    "rpromoter" -> 22, "rarrow" -> 23, "larrow" -> 24, "lpromoter" -> 25)

  private inline def interp(t: Double, p0: P, p1: P): P =
    (p0._1 + t * (p1._1 - p0._1), p0._2 + t * (p1._2 - p0._2))

  /** `alloc_interpolation_points(AF, sides, style, rounded=false)`: 3 points per
    * side — the corner, then the t and (1−t) interpolants — plus a 3-point
    * wraparound. `t = rbconst/d`, halved for DOGEAR, thirded for BOX3D/COMPONENT. */
  private def allocInterp(af: Vector[P], sides: Int, code: Int): Array[P] =
    var rbconst = 12.0 // RBCONST
    var seg = 0
    while seg < sides do
      val p0 = af(seg); val p1 = af((seg + 1) % sides)
      rbconst = math.min(rbconst, math.hypot(p1._1 - p0._1, p1._2 - p0._2) / 3.0)
      seg += 1
    val b = new Array[P](3 * sides + 3)
    var i = 0
    seg = 0
    while seg < sides do
      val p0 = af(seg); val p1 = af((seg + 1) % sides)
      val d = math.hypot(p1._1 - p0._1, p1._2 - p0._2)
      var t = rbconst / d
      if code == 4 || code == 5 then t /= 3       // BOX3D, COMPONENT
      else if code == 1 then t /= 2               // DOGEAR
      b(i) = p0; i += 1
      b(i) = interp(t, p0, p1); i += 1
      b(i) = interp(1.0 - t, p0, p1); i += 1
      seg += 1
    b(i) = b(0); i += 1; b(i) = b(1); i += 1; b(i) = b(2)
    b

  /** `diagonals_draw` (M-variants): the plain polygon plus a short diagonal at
    * each corner (the B[3seg+2]→B[3seg+4] segment). */
  def diagonals(af: Vector[P], sides: Int, filled: Boolean): List[Op] =
    val b   = allocInterp(af, sides, 0)
    val ops = collection.mutable.ListBuffer[Op](Op.Poly(af, filled))
    var seg = 0
    while seg < sides do
      ops += Op.Line(Vector(b(3 * seg + 2), b(3 * seg + 4)))
      seg += 1
    ops.toList

  /** `round_corners` for a special `option.shape` code. `af` = the 4 box
    * corners (node-local, y-up). */
  def apply(af: Vector[P], code: Int, filled: Boolean): List[Op] =
    val sides = af.length // 4 for every special-corner shape
    val b     = allocInterp(af, sides, code)
    val ops   = collection.mutable.ListBuffer.empty[Op]
    def poly(d: Array[P], n: Int): Unit = ops += Op.Poly(d.take(n).toVector, filled)
    def line(pts: P*): Unit             = ops += Op.Line(pts.toVector)
    val mx    = (af(0)._1 + af(1)._1) / 2.0 // mid_x(AF)
    val myA1  = (af(1)._2 + af(2)._2) / 2.0 // mid_y(&AF[1])
    // common "width" units used by the SBOL cases
    def wX = b(2)._1 - b(3)._1
    def wY = b(3)._2 - b(4)._2

    code match
      case 1 => // DOGEAR
        val d = new Array[P](sides + 1)
        var s = 1; while s < sides do { d(s) = af(s); s += 1 }
        d(0)     = b(3 * (sides - 1) + 4)
        d(sides) = b(3 * (sides - 1) + 2)
        poly(d, sides + 1)
        val ss = sides - 1
        val c0 = b(3 * ss + 2)
        val c1 = b(3 * ss + 4)
        val c2 = (c1._1 + (c0._1 - b(3 * ss + 3)._1), c1._2 + (c0._2 - b(3 * ss + 3)._2))
        line(c1, c2)
        line(c0, c2)

      case 2 => // TAB
        val d = new Array[P](sides + 2)
        d(0) = af(0)
        d(1) = b(2)
        d(2) = (b(2)._1 + (b(3)._1 - b(4)._1) / 3, b(2)._2 + (b(3)._2 - b(4)._2) / 3)
        d(3) = (b(3)._1 + (b(3)._1 - b(4)._1) / 3, b(3)._2 + (b(3)._2 - b(4)._2) / 3)
        var s = 4; while s < sides + 2 do { d(s) = af(s - 2); s += 1 }
        poly(d, sides + 2)
        line(b(3), b(2))

      case 3 => // FOLDER
        val d = new Array[P](sides + 3)
        d(0) = af(0)
        d(1) = (af(0)._1 - (af(0)._1 - b(1)._1) / 4, af(0)._2 + (b(3)._2 - b(4)._2) / 3)
        d(2) = (af(0)._1 - 2 * (af(0)._1 - b(1)._1), d(1)._2)
        d(3) = (af(0)._1 - 2.25 * (af(0)._1 - b(1)._1), b(3)._2)
        d(4) = (b(3)._1, b(3)._2)
        var s = 4; while s < sides + 3 do { d(s) = af(s - 3); s += 1 }
        poly(d, sides + 3)

      case 4 => // BOX3D
        val d = new Array[P](sides + 2)
        d(0) = af(0); d(1) = b(2); d(2) = b(4); d(3) = af(2); d(4) = b(8); d(5) = b(10)
        poly(d, sides + 2)
        val c0 = (b(1)._1 + (b(11)._1 - b(0)._1), b(1)._2 + (b(11)._2 - b(0)._2))
        line(c0, b(4))
        line(c0, b(8))
        line(c0, b(0))

      case 5 => // COMPONENT
        val d = new Array[P](sides + 8)
        d(0) = af(0); d(1) = af(1)
        d(2) = (b(3)._1 + (b(4)._1 - b(3)._1), b(3)._2 + (b(4)._2 - b(3)._2))
        d(3) = (d(2)._1 + (b(3)._1 - b(2)._1), d(2)._2 + (b(3)._2 - b(2)._2))
        d(4) = (d(3)._1 + (b(4)._1 - b(3)._1), d(3)._2 + (b(4)._2 - b(3)._2))
        d(5) = (d(4)._1 + (d(2)._1 - d(3)._1), d(4)._2 + (d(2)._2 - d(3)._2))
        d(9) = (b(6)._1 + (b(5)._1 - b(6)._1), b(6)._2 + (b(5)._2 - b(6)._2))
        d(8) = (d(9)._1 + (b(6)._1 - b(7)._1), d(9)._2 + (b(6)._2 - b(7)._2))
        d(7) = (d(8)._1 + (b(5)._1 - b(6)._1), d(8)._2 + (b(5)._2 - b(6)._2))
        d(6) = (d(7)._1 + (d(9)._1 - d(8)._1), d(7)._2 + (d(9)._2 - d(8)._2))
        d(10) = af(2); d(11) = af(3)
        poly(d, sides + 8)
        line(d(2), (d(2)._1 - (d(3)._1 - d(2)._1), d(2)._2 - (d(3)._2 - d(2)._2)),
             ((d(2)._1 - (d(3)._1 - d(2)._1)) + (d(4)._1 - d(3)._1),
              (d(2)._2 - (d(3)._2 - d(2)._2)) + (d(4)._2 - d(3)._2)), d(5))
        line(d(6), (d(6)._1 - (d(7)._1 - d(6)._1), d(6)._2 - (d(7)._2 - d(6)._2)),
             ((d(6)._1 - (d(7)._1 - d(6)._1)) + (d(8)._1 - d(7)._1),
              (d(6)._2 - (d(7)._2 - d(6)._2)) + (d(8)._2 - d(7)._2)), d(9))

      case 6 => // PROMOTER
        val d = new Array[P](sides + 5)
        d(0) = (mx + (af(0)._1 - af(1)._1) / 8, myA1 + wY * 3 / 2)
        d(1) = (mx - (af(0)._1 - af(1)._1) / 4, d(0)._2)
        d(2) = (d(1)._1, myA1)
        d(3) = (d(2)._1 + wX / 2, myA1)
        d(4) = (d(3)._1, myA1 + wY)
        d(5) = (d(0)._1, d(4)._2)
        d(6) = (d(0)._1, d(4)._2 - wY / 4)
        d(7) = (d(6)._1 + wX, d(6)._2 + wY / 2)
        d(8) = (d(0)._1, d(0)._2 + wY / 4)
        poly(d, sides + 5)
        line((af(1)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 7 => // CDS
        val d = new Array[P](sides + 1)
        d(0) = (b(1)._1, b(1)._2 - wY / 2)
        d(1) = (b(3)._1, b(3)._2 - wY / 2)
        d(2) = (af(2)._1, af(2)._2 + wY / 2)
        d(3) = (b(1)._1, af(2)._2 + wY / 2)
        d(4) = (af(0)._1, af(0)._2 - (af(0)._2 - af(3)._2) / 2)
        poly(d, sides + 1)

      case 8 => // TERMINATOR
        val d = new Array[P](sides + 4)
        d(0) = (mx + wX / 4, myA1)
        d(1) = (d(0)._1, d(0)._2 + wY / 2)
        d(2) = (d(1)._1 + wX / 2, d(1)._2)
        d(3) = (d(2)._1, d(2)._2 + wY / 2)
        d(4) = (mx - wX * 3 / 4, d(3)._2)
        d(5) = (d(4)._1, d(2)._2)
        d(6) = (mx - wX / 4, d(1)._2)
        d(7) = (d(6)._1, d(0)._2)
        poly(d, sides + 4)
        line((af(1)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 9 => // UTR
        val d = new Array[P](sides + 2)
        d(0) = (mx + wX * 3 / 4, myA1)
        d(1) = (d(0)._1, d(0)._2 + wY / 4)
        d(2) = (mx + wX / 4, d(1)._2 + wY / 2)
        d(3) = (mx - wX / 4, d(2)._2)
        d(4) = (mx - wX * 3 / 4, d(1)._2)
        d(5) = (d(4)._1, d(0)._2)
        poly(d, sides + 2)
        line((af(1)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 10 => // PRIMERSITE
        val d = new Array[P](sides + 1)
        d(0) = (mx + wX, myA1 + wY / 4)
        d(1) = (d(0)._1 - wX, d(0)._2 + wY)
        d(2) = (d(1)._1, d(0)._2 + wY / 2)
        d(3) = (mx - (af(0)._1 - af(1)._1) / 4, d(2)._2)
        d(4) = (d(3)._1, d(0)._2)
        poly(d, sides + 1)
        line((af(1)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 11 => // RESTRICTIONSITE
        val d = new Array[P](sides + 4)
        d(0) = (mx + (af(0)._1 - af(1)._1) / 8 + wX / 2, myA1 + wY / 4)
        d(1) = (mx - (af(0)._1 - af(1)._1) / 8, d(0)._2)
        d(2) = (d(1)._1, d(1)._2 + wY / 2)
        d(3) = (d(2)._1 - wX / 2, d(2)._2)
        d(4) = (d(3)._1, myA1 - wY / 4)
        d(5) = (d(0)._1 - wX / 2, d(4)._2)
        d(6) = (d(5)._1, d(5)._2 - wY / 2)
        d(7) = (d(0)._1, d(6)._2)
        poly(d, sides + 4)
        line((af(1)._1, myA1), (d(4)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))
        line((d(7)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 12 => // FIVEPOVERHANG
        val d = new Array[P](sides)
        d(0) = (af(1)._1, myA1 + wY / 8)
        d(1) = (d(0)._1 + 2 * wX, d(0)._2)
        d(2) = (d(1)._1, d(1)._2 + wY / 2)
        d(3) = (d(0)._1, d(2)._2)
        poly(d, sides)
        val e = new Array[P](sides)
        e(0) = (af(1)._1 + wX, myA1 - wY * 5 / 8)
        e(1) = (e(0)._1 + wX, e(0)._2)
        e(2) = (e(1)._1, e(1)._2 + wY / 2)
        e(3) = (e(0)._1, e(2)._2)
        poly(e, sides)
        line((e(1)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 13 => // THREEPOVERHANG
        val d = new Array[P](sides)
        d(0) = (af(0)._1, myA1 + wY / 8)
        d(1) = (d(0)._1, d(0)._2 + wY / 2)
        d(2) = (d(1)._1 - 2 * wY, d(1)._2)
        d(3) = (d(2)._1, d(0)._2)
        poly(d, sides)
        val e = new Array[P](sides)
        e(0) = (af(0)._1 - wX, myA1 - wY * 5 / 8)
        e(1) = (e(0)._1, e(0)._2 + wY / 2)
        e(2) = (e(1)._1 - wY, e(1)._2)
        e(3) = (e(2)._1, e(0)._2)
        poly(e, sides)
        line((af(1)._1, myA1), (e(3)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 14 => // NOVERHANG
        def rect(x0: Double, y0: Double): Array[P] =
          val r = new Array[P](sides)
          r(0) = (x0, y0); r(1) = (x0 + wX, y0)
          r(2) = (r(1)._1, r(1)._2 + wY / 2); r(3) = (x0, r(2)._2); r
        val ul = rect(mx - wX * 9 / 8, myA1 + wY / 8); poly(ul, sides)
        val ll = rect(mx - wX * 9 / 8, myA1 - wY * 5 / 8); poly(ll, sides)
        val lr = rect(mx + wX / 8, myA1 - wY * 5 / 8); poly(lr, sides)
        val ur = rect(mx + wX / 8, myA1 + wY / 8); poly(ur, sides)
        line((lr(1)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))
        line((mx - wX * 9 / 8, myA1), (af(1)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 15 => // ASSEMBLY
        def rect(y0: Double): Array[P] =
          val x0 = mx - wX
          val r = new Array[P](sides)
          r(0) = (x0, y0); r(1) = (x0 + 2 * wX, y0)
          r(2) = (r(1)._1, r(1)._2 + wY / 2); r(3) = (x0, r(2)._2); r
        val up = rect(myA1 + wY / 8); poly(up, sides)
        val lo = rect(myA1 - wY * 5 / 8); poly(lo, sides)
        line((up(1)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))
        line((af(1)._1, myA1), (up(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 16 => // SIGNATURE
        val d = new Array[P](sides)
        d(0) = (af(0)._1, b(1)._2 - wY / 2)
        d(1) = (b(3)._1, b(3)._2 - wY / 2)
        d(2) = (af(2)._1, af(2)._2 + wY / 2)
        d(3) = (af(0)._1, af(2)._2 + wY / 2)
        poly(d, sides)
        val c0a = (af(1)._1 + wX / 4, myA1 + wY / 8)
        line(c0a, (c0a._1 + wX / 4, c0a._2 - wY / 4))
        val c0b = (af(1)._1 + wX / 4, myA1 - wY / 8)
        line(c0b, (c0b._1 + wX / 4, c0b._2 + wY / 4))
        val c0c = (af(1)._1 + wX / 4, af(2)._2 + wY * 3 / 4)
        line(c0c, (af(0)._1 - wX / 4, c0c._2))

      case 17 => // INSULATOR
        val d = new Array[P](sides)
        d(0) = (mx + wX / 2, myA1 + wX / 2)
        d(1) = (d(0)._1, myA1 - wX / 2)
        d(2) = (mx - wX / 2, d(1)._2)
        d(3) = (d(2)._1, d(0)._2)
        poly(d, sides)
        val o0 = (mx + wX * 3 / 4, myA1 + wX * 3 / 4)
        val o1 = (o0._1, myA1 - wX * 3 / 4)
        val o2 = (mx - wX * 3 / 4, o1._2)
        val o3 = (o2._1, o0._2)
        line(o0, o1, o2, o3, o0)
        line((mx + wX * 3 / 4, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))
        line((af(1)._1, myA1), (mx - wX * 3 / 4, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 18 => // RIBOSITE
        val d = new Array[P](sides + 12)
        d(0) = (mx + wX / 4, myA1 + wY / 2)
        d(1) = (d(0)._1, d(0)._2 + wY / 8)
        d(2) = (d(0)._1 - wX / 8, d(1)._2 + wY / 8)
        d(3) = (d(0)._1, d(2)._2 + wY / 8)
        d(4) = (d(0)._1, d(3)._2 + wY / 8)
        d(5) = (d(2)._1, d(4)._2)
        d(6) = (mx, d(3)._2)
        d(7) = (d(6)._1 - wX / 8, d(5)._2)
        d(8) = (d(7)._1 - wX / 8, d(7)._2)
        d(9) = (d(8)._1, d(3)._2)
        d(10) = (d(8)._1 + wX / 8, d(2)._2)
        d(11) = (d(8)._1, d(1)._2)
        d(12) = (d(8)._1, d(0)._2)
        d(13) = (d(10)._1, d(12)._2)
        d(14) = (d(6)._1, d(1)._2)
        d(15) = (d(2)._1, d(0)._2)
        poly(d, sides + 12)
        line((d(14)._1, myA1), (d(14)._1, myA1 + wY / 8))
        line((d(14)._1, myA1 + wY / 4), (d(14)._1, myA1 + wY / 4 + wY / 8))
        line((af(1)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 19 => // RNASTAB
        val d = new Array[P](sides + 4)
        d(0) = (mx + wX / 8, myA1 + wY / 2)
        d(1) = (d(0)._1 + wX / 8, d(0)._2 + wY / 8)
        d(2) = (d(1)._1, d(1)._2 + wY / 4)
        d(3) = (d(0)._1, d(2)._2 + wY / 8)
        d(4) = (d(3)._1 - wX / 4, d(3)._2)
        d(5) = (d(4)._1 - wX / 8, d(2)._2)
        d(6) = (d(5)._1, d(1)._2)
        d(7) = (d(4)._1, d(0)._2)
        poly(d, sides + 4)
        line((mx, myA1), (mx, myA1 + wY / 8))
        line((mx, myA1 + wY / 4), (mx, myA1 + wY / 4 + wY / 8))
        line((af(1)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 20 => // PROTEASESITE
        val d = new Array[P](sides + 12)
        d(0) = (mx + wX / 4, myA1 + wY / 2)
        d(1) = (d(0)._1, d(0)._2 + wY / 8)
        d(2) = (d(0)._1 - wX / 8, d(1)._2 + wY / 8)
        d(3) = (d(0)._1, d(2)._2 + wY / 8)
        d(4) = (d(0)._1, d(3)._2 + wY / 8)
        d(5) = (d(2)._1, d(4)._2)
        d(6) = (mx, d(3)._2)
        d(7) = (d(6)._1 - wX / 8, d(5)._2)
        d(8) = (d(7)._1 - wX / 8, d(7)._2)
        d(9) = (d(8)._1, d(3)._2)
        d(10) = (d(8)._1 + wX / 8, d(2)._2)
        d(11) = (d(8)._1, d(1)._2)
        d(12) = (d(8)._1, d(0)._2)
        d(13) = (d(10)._1, d(12)._2)
        d(14) = (d(6)._1, d(1)._2)
        d(15) = (d(2)._1, d(0)._2)
        poly(d, sides + 12)
        line(d(14), (d(14)._1, myA1))
        line((af(1)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 21 => // PROTEINSTAB
        val d = new Array[P](sides + 4)
        d(0) = (mx + wX / 8, myA1 + wY / 2)
        d(1) = (d(0)._1 + wX / 8, d(0)._2 + wY / 8)
        d(2) = (d(1)._1, d(1)._2 + wY / 4)
        d(3) = (d(0)._1, d(2)._2 + wY / 8)
        d(4) = (d(3)._1 - wX / 4, d(3)._2)
        d(5) = (d(4)._1 - wX / 8, d(2)._2)
        d(6) = (d(5)._1, d(1)._2)
        d(7) = (d(4)._1, d(0)._2)
        poly(d, sides + 4)
        line((mx, d(0)._2), (mx, myA1))
        line((af(1)._1, myA1), (af(0)._1, af(2)._2 + (af(0)._2 - af(3)._2) / 2))

      case 22 => // RPROMOTER
        val d = new Array[P](sides + 5)
        d(0) = (b(1)._1 - wX / 2, b(1)._2 - wY / 2)
        d(1) = (b(3)._1, b(3)._2 - wY / 2)
        d(2) = (af(2)._1, af(2)._2)
        d(3) = (b(2)._1 + wX / 2, af(2)._2)
        d(4) = (b(2)._1 + wX / 2, af(2)._2 + wY / 2)
        d(5) = (b(1)._1 - wX / 2, af(2)._2 + wY / 2)
        d(6) = (b(1)._1 - wX / 2, af(3)._2)
        d(7) = (af(0)._1, af(0)._2 - (af(0)._2 - af(3)._2) / 2)
        d(8) = (b(1)._1 - wX / 2, af(0)._2)
        poly(d, sides + 5)

      case 23 => // RARROW
        val d = new Array[P](sides + 3)
        d(0) = (b(1)._1 - wX / 2, b(1)._2 - wY / 2)
        d(1) = (b(3)._1, b(3)._2 - wY / 2)
        d(2) = (af(2)._1, af(2)._2 + wY / 2)
        d(3) = (b(1)._1 - wX / 2, af(2)._2 + wY / 2)
        d(4) = (b(1)._1 - wX / 2, af(3)._2)
        d(5) = (af(0)._1, af(0)._2 - (af(0)._2 - af(3)._2) / 2)
        d(6) = (b(1)._1 - wX / 2, af(0)._2)
        poly(d, sides + 3)

      case 24 => // LARROW
        val d = new Array[P](sides + 3)
        d(0) = (af(0)._1, af(0)._2 - wY / 2)
        d(1) = (b(2)._1 + wX / 2, af(0)._2 - wY / 2)
        d(2) = (b(2)._1 + wX / 2, b(2)._2)
        d(3) = (af(1)._1, af(1)._2 - (af(1)._2 - af(2)._2) / 2)
        d(4) = (b(2)._1 + wX / 2, af(2)._2)
        d(5) = (b(2)._1 + wX / 2, af(2)._2 + wY / 2)
        d(6) = (af(0)._1, af(3)._2 + wY / 2)
        poly(d, sides + 3)

      case 25 => // LPROMOTER
        val d = new Array[P](sides + 5)
        d(0) = (af(0)._1, af(0)._2 - wY / 2)
        d(1) = (b(2)._1 + wX / 2, af(0)._2 - wY / 2)
        d(2) = (b(2)._1 + wX / 2, b(2)._2)
        d(3) = (af(1)._1, af(1)._2 - (af(1)._2 - af(2)._2) / 2)
        d(4) = (b(2)._1 + wX / 2, af(2)._2)
        d(5) = (b(2)._1 + wX / 2, af(2)._2 + wY / 2)
        d(6) = (b(1)._1 - wX / 2, af(3)._2 + wY / 2)
        d(7) = (b(1)._1 - wX / 2, af(3)._2)
        d(8) = (af(3)._1, af(3)._2)
        poly(d, sides + 5)

      case _ => ()

    ops.toList

end RoundCorners
