package org.jpablo.graphexplorer.graphviz.layout

/** Pure-Scala port of Graphviz `poly_init` vertex generation + final sizing
  * for the **convex builtin** polygon shapes (lib/common/shapes.c, gv 13.0.1):
  * triangle, invtriangle, diamond, trapezium, invtrapezium, parallelogram,
  * pentagon, house, invhouse, hexagon, septagon, octagon, doubleoctagon,
  * tripleoctagon, egg, plus the generic `polygon` shape (user-controlled
  * sides/skew/distortion/orientation/regular/peripheries).
  *
  * These share `poly_fns` with box/ellipse but differ in `sides`, `orientation`
  * (rotation, deg), `distortion`, `skew` and `peripheries` (concentric drawn
  * outlines). Ellipse/circle (`sides<=2`, undistorted) render as `<ellipse>`
  * and box-family (axis-aligned `sides==4`) as a rectangle — both handled
  * directly in [[NodeSize]]/`Svg`; this module covers the rotated / distorted /
  * n-gon / multi-periphery shapes that need explicit vertices. A `sides<=2`
  * shape that is distorted or skewed (e.g. `egg`) is promoted to a 120-gon
  * (poly_init), so it too flows through here.
  *
  * The special-option shapes (note/tab/folder/box3d/component/cylinder, the SBOL
  * biology set, star, the M-variants) set `polygon.option.shape != 0` /
  * `diagonals` / a custom `vertices` generator and are handled in later
  * increments; their standard sides=4 *sizing* still routes through here.
  */
object Polygon:

  private val Sqrt2 = 1.41421356237309504880 // arith.h SQRT2

  /** Builtin polygon descriptor (shapes.c `p_*` table). `peripheries` is the
    * drawn-outline count (most convex builtins use 1; doubleoctagon 2,
    * tripleoctagon 3). `sides==0` marks the generic `polygon` shape whose
    * geometry is taken entirely from user attributes. */
  /** Custom vertex/size generators (`poly_desc_t`): star and cylinder replace
    * the regular-n-gon size + vertex computation with their own. */
  enum Gen derives CanEqual:
    case Star, Cylinder

  final case class Desc(
      sides:       Int,
      peripheries: Int,
      orientation: Double, // degrees
      distortion:  Double,
      skew:        Double,
      regular:     Boolean = false,
      gen:         Option[Gen] = None
  ) derives CanEqual

  /** @return descriptor for a supported builtin routed through [[init]], else
    *         `None`. The generic `polygon` shape resolves here to its base
    *         `Desc(sides=0)`; [[NodeSize]] then overlays the user attributes. */
  def descOf(name: String): Option[Desc] = name.toLowerCase match
    case "triangle"      => Some(Desc(3, 1, 0.0, 0.0, 0.0))
    case "invtriangle"   => Some(Desc(3, 1, 180.0, 0.0, 0.0))
    case "diamond"       => Some(Desc(4, 1, 45.0, 0.0, 0.0))
    case "trapezium"     => Some(Desc(4, 1, 0.0, -0.4, 0.0))
    case "invtrapezium"  => Some(Desc(4, 1, 180.0, -0.4, 0.0))
    case "parallelogram" => Some(Desc(4, 1, 0.0, 0.0, 0.6))
    case "pentagon"      => Some(Desc(5, 1, 0.0, 0.0, 0.0))
    case "house"         => Some(Desc(5, 1, 0.0, -0.64, 0.0))
    case "invhouse"      => Some(Desc(5, 1, 180.0, -0.64, 0.0))
    case "hexagon"       => Some(Desc(6, 1, 0.0, 0.0, 0.0))
    case "septagon"      => Some(Desc(7, 1, 0.0, 0.0, 0.0))
    case "octagon"       => Some(Desc(8, 1, 0.0, 0.0, 0.0))
    case "doubleoctagon" => Some(Desc(8, 2, 0.0, 0.0, 0.0))
    case "tripleoctagon" => Some(Desc(8, 3, 0.0, 0.0, 0.0))
    case "egg"           => Some(Desc(1, 1, 0.0, -0.3, 0.0))
    case "mdiamond"      => Some(Desc(4, 1, 45.0, 0.0, 0.0)) // diamond + diagonals
    case "star"          => Some(Desc(10, 1, 0.0, 0.0, 0.0, gen = Some(Gen.Star)))
    case "cylinder"      => Some(Desc(19, 1, 0.0, 0.0, 0.0, gen = Some(Gen.Cylinder)))
    case "polygon"       => Some(Desc(0, 1, 0.0, 0.0, 0.0))
    case _               => None

  // star generator constants (shapes.c: alpha = π/10).
  private val Alpha  = math.Pi / 10.0
  private val Alpha2 = 2 * Alpha
  private val Alpha3 = 3 * Alpha
  private val Alpha4 = 2 * Alpha2

  /** `star_size`: smallest star bb containing the label box. */
  private def starSize(x: Double, y: Double): (Double, Double) =
    val rx = x / (2 * math.cos(Alpha))
    val ry = y / (math.sin(Alpha) + math.sin(Alpha3))
    val r0 = math.max(rx, ry)
    val r  = r0 * math.sin(Alpha4) * math.cos(Alpha2) / (math.cos(Alpha) * math.cos(Alpha4))
    (2 * r * math.cos(Alpha), r * (1 + math.sin(Alpha3)))

  /** `star_vertices`: 10 alternating outer/inner-radius points (bb readjusted to
    * the star aspect ratio, returned alongside the vertices). */
  private def starVertices(bbX: Double, bbY: Double): (Array[(Double, Double)], (Double, Double)) =
    var sx = bbX; var sy = bbY
    val aspect = (1 + math.sin(Alpha3)) / (2 * math.cos(Alpha))
    val a = sy / sx
    if a > aspect then sx = sy / aspect
    else if a < aspect then sy = sx * aspect
    val r      = sx / (2 * math.cos(Alpha))
    val r0     = r * math.cos(Alpha) * math.cos(Alpha4) / (math.sin(Alpha4) * math.cos(Alpha2))
    val offset = (r * (1 - math.sin(Alpha3))) / 2
    val v = Array.ofDim[(Double, Double)](10)
    var theta = Alpha
    var i = 0
    while i < 10 do
      v(i)     = (r * math.cos(theta), r * math.sin(theta) - offset)
      theta += Alpha2
      v(i + 1) = (r0 * math.cos(theta), r0 * math.sin(theta) - offset)
      theta += Alpha2
      i += 2
    (v, (sx, sy))

  /** `cylinder_size`: grow the height by 1.375 to make room for the top cap. */
  private def cylinderSize(x: Double, y: Double): (Double, Double) = (x, y * 1.375)

  /** `cylinder_vertices`: 19 bezier control points (a rounded-top/bottom tube),
    * with repeated points at the degenerate "straight side" seams. */
  private def cylinderVertices(bbX: Double, bbY: Double): (Array[(Double, Double)], (Double, Double)) =
    val x = bbX / 2; val y = bbY / 2; val yr = bbY / 11
    val v = Array.ofDim[(Double, Double)](19)
    v(0)  = (x, y - yr)
    v(1)  = (x, y - (1 - 0.551784) * yr)
    v(2)  = (0.551784 * x, y)
    v(3)  = (0.0, y)
    v(4)  = (-0.551784 * x, y)
    v(5)  = (-x, v(1)._2)
    v(6)  = (-x, y - yr)
    v(7)  = v(6)
    v(8)  = (-x, yr - y)
    v(9)  = v(8)
    v(10) = (-x, -v(1)._2)
    v(11) = (v(4)._1, -v(4)._2)
    v(12) = (v(3)._1, -v(3)._2)
    v(13) = (v(2)._1, -v(2)._2)
    v(14) = (v(1)._1, -v(1)._2)
    v(15) = (v(0)._1, -v(0)._2)
    v(16) = v(15)
    v(17) = v(0)
    v(18) = v(0)
    (v, (bbX, bbY))

  private val Gap       = 4.0 // const.h GAP
  private val PenWidth  = 1.0 // DEFAULT_NODEPENWIDTH (non-default penwidth deferred)

  /** Result of `poly_init` for a convex builtin: final node bounding box (pt),
    * the drawn peripheries (each a centred, y-up vertex ring; `rings.head` is
    * the innermost/label-fitting periphery, `rings.last` the outermost drawn
    * one), and the `outline` periphery = outermost ring pushed out by
    * penwidth/2 along each vertex bisector. `poly_inside` clips edge splines to
    * the OUTLINE, not the drawn polygon (shapes.c: `outp = peripheries*sides`). */
  final case class Poly(
      bbX:      Double,
      bbY:      Double,
      rings:    Vector[Vector[(Double, Double)]],
      outline:  Vector[(Double, Double)]
  ):
    /** Innermost periphery — the label-fitting ring; used for image placement
      * and (peripheries==1) the single drawn outline. */
    def vertices: Vector[(Double, Double)] = rings.head

  /** Port of the size-and-vertices core of `poly_init` (convex branch).
    *
    * @param dimenX,dimenY  padded label box (points) — `dimen` after PAD/margin
    * @param minW,minH      min node size (points) = `INCH2PS(width/height)` attrs
    * @param valignCentered `ND_label->valign == 'c'` (no `labelloc=t|b`)
    * @param regular        `regular` attr / shape flag ⇒ force square
    */
  def init(
      dimenX:         Double,
      dimenY:         Double,
      minW:           Double,
      minH:           Double,
      valignCentered: Boolean,
      regular:        Boolean,
      d:              Desc
  ): Poly =
    // sides<=2 shape that is distorted/skewed ⇒ approximate by a 120-gon
    // (poly_init: "I don't know how to distort or skew ellipses in postscript").
    var sides =
      if d.sides <= 2 && (d.distortion != 0.0 || d.skew != 0.0) then 120
      else if d.sides < 3 then 3 // defensive: non-distorted sides<=2 never reach here
      else d.sides
    val orientation = d.orientation
    val distortion  = d.distortion
    val skew        = d.skew
    val peripheries = math.max(d.peripheries, 0)

    var bbX = dimenX
    var bbY = dimenY

    // isBox: axis-aligned undistorted quad ⇒ exact fit (none of our shapes).
    val isBox =
      sides == 4 && math.abs(orientation % 90) < 0.5 &&
        distortion == 0.0 && skew == 0.0

    if isBox then ()
    else d.gen match
      case Some(g) =>
        // custom generator (star/cylinder): its own smallest-containing size.
        val (gx, gy) = g match
          case Gen.Star     => starSize(bbX, bbY)
          case Gen.Cylinder => cylinderSize(bbX, bbY)
        bbX = gx; bbY = gy
      case None =>
        // smallest ellipse containing the label box (SQRT2), with the
        // spare-height optimisation when the label is vertically centred, then
        // fit-in-polygon inflation 1/cos(pi/sides).
        val temp = bbY * Sqrt2
        if minH > temp && valignCentered then
          bbX *= math.sqrt(1.0 / (1.0 - sqr(bbY / minH)))
        else
          bbX *= Sqrt2
          bbY = temp
        if sides > 2 then
          val t = math.cos(math.Pi / sides)
          bbX /= t
          bbY /= t

    var width  = math.max(minW, bbX)
    var height = math.max(minH, bbY)
    bbX = width
    bbY = height
    if regular then
      val s = math.max(bbX, bbY)
      width = s; height = s; bbX = s; bbY = s

    // ── base periphery generation ──
    val raw  = Array.ofDim[Double](sides, 2)
    var xmax = 0.0
    var ymax = 0.0
    d.gen match
      case Some(g) =>
        // custom vertex generator sets the raw vertices + its own bb; the
        // half-extents are bb/2 (poly_init: xmax = bb.x/2 after vertex_gen).
        val (gv, gbb) = g match
          case Gen.Star     => starVertices(bbX, bbY)
          case Gen.Cylinder => cylinderVertices(bbX, bbY)
        var k = 0
        while k < sides do { raw(k)(0) = gv(k)._1; raw(k)(1) = gv(k)._2; k += 1 }
        xmax = gbb._1 / 2.0
        ymax = gbb._2 / 2.0
      case None =>
        // regular n-gon with distortion/skew/orientation.
        val sectorangle = 2.0 * math.Pi / sides
        val sidelength  = math.sin(sectorangle / 2.0)
        val skewdist    = math.hypot(math.abs(distortion) + math.abs(skew), 1.0)
        val gdistortion = distortion * Sqrt2 / math.cos(sectorangle / 2.0)
        val gskew       = skew / 2.0
        var angle       = (sectorangle - math.Pi) / 2.0
        var rx          = 0.5 * math.cos(angle)
        var ry          = 0.5 * math.sin(angle)
        angle += (math.Pi - sectorangle) / 2.0
        var i = 0
        while i < sides do
          angle += sectorangle
          rx += sidelength * math.cos(angle)
          ry += sidelength * math.sin(angle)
          // distort and skew
          var px = rx * (skewdist + ry * gdistortion) + ry * gskew
          var py = ry
          // orient
          val alpha = math.toRadians(orientation) + math.atan2(py, px)
          val h     = math.hypot(px, py)
          px = h * math.cos(alpha)
          py = h * math.sin(alpha)
          // scale for label
          px *= bbX
          py *= bbY
          xmax = math.max(math.abs(px), xmax)
          ymax = math.max(math.abs(py), ymax)
          raw(i)(0) = px
          raw(i)(1) = py
          i += 1

    xmax *= 2.0
    ymax *= 2.0
    bbX = math.max(width, xmax)
    bbY = math.max(height, ymax)
    val scalex = bbX / xmax
    val scaley = bbY / ymax

    // base ring scaled to the (label-fitting) bb
    val base = Array.tabulate(sides)(k => (raw(k)(0) * scalex, raw(k)(1) * scaley))

    // ── concentric peripheries + penwidth outline (poly_init bisector loop) ──
    // `outp` = # of vertex rings to synthesise beyond nothing: at least the
    // drawn peripheries, +1 for the penwidth outline when peripheries>=1.
    val outp = if peripheries >= 1 then peripheries + 1 else math.max(peripheries, 1)
    // rings(0)=base, rings(j)=base offset out by j*GAP along each bisector,
    // rings(outp-1)=the penwidth outline.
    val rings = Array.fill(outp)(Array.ofDim[Double](sides, 2))
    var s = 0
    while s < sides do { rings(0)(s)(0) = base(s)._1; rings(0)(s)(1) = base(s)._2; s += 1 }

    if outp > 1 then
      // seed beta from the first side ending at vertices[0] (scan back to the
      // first distinct predecessor — all distinct for convex builtins).
      val R0 = base(0)
      var qIdx = sides - 1
      var jj = 1
      while jj < sides && base((sides - jj) % sides) == R0 do { qIdx = (sides - jj - 1 + sides) % sides; jj += 1 }
      var Q = base((sides - 1) % sides)
      var beta = math.atan2(R0._2 - Q._2, R0._1 - Q._1)
      var qprev = Q
      var cosx = 0.0
      var sinx = 0.0
      var i2 = 0
      while i2 < sides do
        val cur = base(i2)
        if cur._1 == qprev._1 && cur._2 == qprev._2 then
          () // degenerate side (point): reuse the previous offset (cylinder case)
        else
          // next distinct vertex forward
          var r = base((i2 + 1) % sides)
          var k = 1
          while k < sides && r == cur do { r = base((i2 + k) % sides); k += 1 }
          val alpha = beta
          beta = math.atan2(r._2 - cur._2, r._1 - cur._1)
          val gamma = (alpha + math.Pi - beta) / 2.0
          val temp  = Gap / math.sin(gamma)
          sinx = math.sin(alpha - gamma) * temp
          cosx = math.cos(alpha - gamma) * temp
        qprev = cur
        // successive drawn peripheries at this base vertex
        var qx = cur._1
        var qy = cur._2
        var j = 1
        while j < peripheries do
          qx += cosx; qy += sinx
          rings(j)(i2)(0) = qx; rings(j)(i2)(1) = qy
          j += 1
        if outp > peripheries then
          qx += cosx * PenWidth / 2.0 / Gap
          qy += sinx * PenWidth / 2.0 / Gap
          rings(peripheries)(i2)(0) = qx; rings(peripheries)(i2)(1) = qy
        i2 += 1

      // grow bb by the outermost DRAWN periphery, outline_bb by the outline.
      var idx = 0
      while idx < sides do
        val p = rings(peripheries - 1)(idx)
        bbX = math.max(2.0 * math.abs(p(0)), bbX)
        bbY = math.max(2.0 * math.abs(p(1)), bbY)
        idx += 1

    val drawn   = Vector.tabulate(math.max(peripheries, 1))(j =>
      Vector.tabulate(sides)(k => (rings(j)(k)(0), rings(j)(k)(1))))
    val outline = Vector.tabulate(sides)(k => (rings(outp - 1)(k)(0), rings(outp - 1)(k)(1)))

    Poly(bbX, bbY, drawn, outline)

  private inline def sqr(x: Double): Double = x * x

end Polygon
