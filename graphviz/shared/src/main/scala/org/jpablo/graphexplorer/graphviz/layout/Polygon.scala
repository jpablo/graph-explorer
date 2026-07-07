package org.jpablo.graphexplorer.graphviz.layout

/** Pure-Scala port of Graphviz `poly_init` vertex generation + final sizing
  * for the **convex builtin** polygon shapes (lib/common/shapes.c, gv 13.0.1):
  * triangle, invtriangle, diamond, trapezium, invtrapezium, parallelogram,
  * pentagon, house, invhouse, hexagon, septagon, octagon.
  *
  * These share `poly_fns` with box/ellipse but differ in `sides`, `orientation`
  * (rotation, deg), `distortion` and `skew`. Ellipse/circle (`sides<=2`) render
  * as `<ellipse>` and box-family (axis-aligned `sides==4`) as a rectangle — both
  * handled directly in [[NodeSize]]/`Svg`; this module covers only the rotated /
  * distorted / n-gon shapes that need explicit vertices.
  *
  * The special-option shapes (note/tab/folder/box3d/component/cylinder, the SBOL
  * biology set, star) set `polygon.option.shape != 0` and are intentionally NOT
  * here — they need `round_corners`/custom vertex generators (later increment).
  */
object Polygon:

  private val Sqrt2 = 1.41421356237309504880 // arith.h SQRT2

  /** Builtin polygon descriptor (shapes.c `p_*` table). `peripheries` is the
    * drawn-outline count (all convex builtins here use 1). */
  final case class Desc(
      sides:       Int,
      peripheries: Int,
      orientation: Double, // degrees
      distortion:  Double,
      skew:        Double
  ) derives CanEqual

  /** @return descriptor for a supported convex builtin, else `None`. */
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
    case _               => None

  /** Result of `poly_init` for a convex builtin: final node bounding box (pt)
    * and the centred (origin at node centre, **y-up**) periphery-0 vertices. */
  final case class Poly(bbX: Double, bbY: Double, vertices: Vector[(Double, Double)])

  /** Port of the size-and-vertices core of `poly_init`.
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
    val sides       = d.sides
    val orientation = d.orientation
    val distortion  = d.distortion
    val skew        = d.skew

    var bbX = dimenX
    var bbY = dimenY

    // isBox: axis-aligned undistorted quad ⇒ exact fit (none of our shapes).
    val isBox =
      sides == 4 && math.abs(orientation % 90) < 0.5 &&
        distortion == 0.0 && skew == 0.0

    if isBox then ()
    else
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

    // ── vertex generation (regular n-gon with distortion/skew/orientation) ──
    val sectorangle = 2.0 * math.Pi / sides
    val sidelength  = math.sin(sectorangle / 2.0)
    val skewdist    = math.hypot(math.abs(distortion) + math.abs(skew), 1.0)
    val gdistortion = distortion * Sqrt2 / math.cos(sectorangle / 2.0)
    val gskew       = skew / 2.0
    var angle       = (sectorangle - math.Pi) / 2.0
    var rx          = 0.5 * math.cos(angle)
    var ry          = 0.5 * math.sin(angle)
    var xmax        = 0.0
    var ymax        = 0.0
    angle += (math.Pi - sectorangle) / 2.0

    val raw = Array.ofDim[Double](sides, 2)
    var i   = 0
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

    val verts = Vector.tabulate(sides)(k => (raw(k)(0) * scalex, raw(k)(1) * scaley))
    Poly(bbX, bbY, verts)

  private inline def sqr(x: Double): Double = x * x

end Polygon
