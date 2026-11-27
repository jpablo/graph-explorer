package org.jpablo.graphexplorer.gxcore.layout

/** The path of an arrow with bend points in a free layout (ADR 0004, part 4).
  *
  * The path is a smooth curve through the bend points, in their order. It starts on the boundary of the source node
  * and it ends at the base of the arrowhead. The tip of the arrowhead is on the boundary of the target node.
  *
  * The curve is a uniform Catmull-Rom spline, written as cubic Bézier segments: one segment between each two points.
  * At an inner point `Pi`, the tangent is `(Pi+1 - Pi-1) / 2`, so the control points are `Pi ± (Pi+1 - Pi-1) / 6`.
  * At an end, a mirror point continues the curve (`P-1 = 2 P0 - P1`), so the curve leaves the end straight toward the
  * next point.
  *
  * The two end points are on the rays from the node centers to the nearest bends. Thus the curve leaves the source
  * away from its center, and the arrowhead points to the center of the target.
  *
  * The functions do not know the axis direction. They work in the frame of the SVG (the y axis down) and in the
  * frame of the layout (the y axis up).
  */
object BendPath:

  /** A point or a vector. */
  final case class Vec(x: Double, y: Double) derives CanEqual:
    def +(o: Vec): Vec       = Vec(x + o.x, y + o.y)
    def -(o: Vec): Vec       = Vec(x - o.x, y - o.y)
    def *(k: Double): Vec    = Vec(x * k, y * k)
    def dot(o: Vec): Double  = x * o.x + y * o.y
    def length: Double       = math.hypot(x, y)

    /** The vector with length 1, or None for the zero vector. */
    def unit: Option[Vec] =
      val l = length
      Option.when(l > 1e-9)(Vec(x / l, y / l))

  /** One cubic Bézier segment from `p0` to `p1`, with the control points `c1` and `c2`. */
  final case class Cubic(p0: Vec, c1: Vec, c2: Vec, p1: Vec) derives CanEqual:

    /** The point of the segment at the parameter `t`, from 0 to 1. */
    def at(t: Double): Vec =
      val u = 1 - t
      p0 * (u * u * u) + c1 * (3 * u * u * t) + c2 * (3 * u * t * t) + p1 * (t * t * t)

  /** The shape of a node, for the end of an arrow. */
  enum NodeShape derives CanEqual:
    case Ellipse(c: Vec, rx: Double, ry: Double)

    /** A convex polygon: a box, a diamond, a hexagon. */
    case Polygon(points: Vector[Vec])

    /** The center of the shape. The center of a polygon is the center of its bounding box. */
    def center: Vec =
      this match
        case Ellipse(c, _, _) => c
        case Polygon(ps) =>
          if ps.isEmpty then Vec(0, 0)
          else Vec((ps.map(_.x).min + ps.map(_.x).max) / 2, (ps.map(_.y).min + ps.map(_.y).max) / 2)

  object NodeShape:
    /** The box with the center `c`, the width `w` and the height `h`. */
    def box(c: Vec, w: Double, h: Double): NodeShape =
      Polygon(Vector(Vec(c.x - w / 2, c.y - h / 2), Vec(c.x + w / 2, c.y - h / 2), Vec(c.x + w / 2, c.y + h / 2), Vec(c.x - w / 2, c.y + h / 2)))

    /** The convex polygon around `points` (their convex hull), in the order of the points. Graphviz draws some shapes
      * as a path, for example a rounded box or a cylinder. The hull of the points of the path (the ends and the
      * control points) contains the curve, and it is near the curve. None for fewer than three points that are not
      * on one line.
      */
    /** The shape of a node that Graphviz draws as the path `d`: the hull of the points of the path. */
    def ofPath(d: String): Option[NodeShape] = hull(pathPoints(d))

    def hull(points: Seq[Vec]): Option[NodeShape] =
      val ps = points.distinct.sortBy(p => (p.x, p.y)).toVector
      def turn(o: Vec, a: Vec, b: Vec): Double = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
      // Andrew's monotone chain: the lower chain, then the upper chain. Each chain keeps only the left turns.
      def chain(in: Vector[Vec]): Vector[Vec] =
        in.foldLeft(Vector.empty[Vec]): (acc, p) =>
          var out = acc
          while out.size >= 2 && turn(out(out.size - 2), out.last, p) <= 0 do out = out.init
          out :+ p
      if ps.size < 3 then None
      else
        val result = chain(ps).init ++ chain(ps.reverse).init
        Option.when(result.size >= 3)(Polygon(result))

  /** One end of an arrow: the tip of its decoration, and the direction of the arrow at the tip, with length 1. The
    * direction points into the node. With no decoration, the tip is the end of the path.
    */
  final case class ArrowEnd(tip: Vec, direction: Vec) derives CanEqual

  /** The route of an arrow: the segments of its path, the arrowhead at the target, and the tail at the source. */
  final case class Route(segments: Vector[Cubic], head: ArrowEnd, tail: ArrowEnd) derives CanEqual

  /** The Catmull-Rom curve through `points`, as cubic segments. Fewer than two points give no segment. */
  def catmullRom(points: Vector[Vec]): Vector[Cubic] =
    if points.size < 2 then Vector.empty
    else
      val n = points.size
      def p(i: Int): Vec =
        if i < 0 then points(0) * 2 - points(1)
        else if i >= n then points(n - 1) * 2 - points(n - 2)
        else points(i)
      Vector.tabulate(n - 1): i =>
        Cubic(p(i), p(i) + (p(i + 1) - p(i - 1)) * (1.0 / 6), p(i + 1) - (p(i + 2) - p(i)) * (1.0 / 6), p(i + 1))

  /** The point where the ray from the center of `shape` to the point `toward` leaves the shape. A point `toward` at
    * the center gives the center.
    */
  def exit(shape: NodeShape, toward: Vec): Vec =
    val c = shape.center
    val d = toward - c
    if d.length <= 1e-9 then c
    else
      shape match
        case NodeShape.Ellipse(_, rx, ry) =>
          if rx <= 0 || ry <= 0 then c
          else c + d * (1 / math.sqrt((d.x / rx) * (d.x / rx) + (d.y / ry) * (d.y / ry)))
        case NodeShape.Polygon(ps) =>
          // The smallest parameter `t > 0` where `c + t d` meets a side `a + u (b - a)`, with `u` from 0 to 1.
          val hits =
            ps.indices.flatMap: i =>
              val a     = ps(i)
              val b     = ps((i + 1) % ps.size)
              val e     = b - a
              val cross = d.x * e.y - d.y * e.x
              if math.abs(cross) <= 1e-12 then None
              else
                val ac = a - c
                val t  = (ac.x * e.y - ac.y * e.x) / cross
                val u  = (ac.x * d.y - ac.y * d.x) / cross
                Option.when(t > 1e-9 && u >= -1e-9 && u <= 1 + 1e-9)(t)
          hits.minOption.fold(c)(t => c + d * t)

  /** The route of an arrow from `source` to `target` through `bends`.
    *
    * @param headLength the length of the arrowhead: the path ends this far before the tip. 0 for no arrowhead.
    * @param tailLength the length of the tail decoration: the path starts this far after the tail tip.
    */
  def route(source: NodeShape, target: NodeShape, bends: Vector[Vec], headLength: Double, tailLength: Double): Route =
    val firstToward = bends.headOption.getOrElse(target.center)
    val lastToward  = bends.lastOption.getOrElse(source.center)
    val tail        = end(exit(source, firstToward), firstToward, source.center)
    val head        = end(exit(target, lastToward), lastToward, target.center)
    // The path stops at the base of a decoration. A bend nearer than the decoration length keeps a short part.
    def base(e: ArrowEnd, from: Vec, length: Double): Vec =
      val room = (e.tip - from).length
      e.tip - e.direction * (length min (room * 0.9)).max(0)
    val points = (base(tail, firstToward, tailLength) +: bends) :+ base(head, lastToward, headLength)
    Route(catmullRom(points), head, tail)

  /** The end at `tip` of an arrow that comes from `from`: its direction points from `from` to the tip, or to the
    * center of the node when the two points are the same.
    */
  private def end(tip: Vec, from: Vec, center: Vec): ArrowEnd =
    ArrowEnd(tip, (tip - from).unit.orElse((center - from).unit).getOrElse(Vec(1, 0)))

  /** The angle of the direction `d`, in degrees, from the x axis toward the y axis. */
  def angleDegrees(d: Vec): Double = math.toDegrees(math.atan2(d.y, d.x))

  /** The move of a decoration (an arrowhead or a tail) from the end `from` to the end `to`: a turn around the old tip
    * by the angle between the two directions, and then a move of the tip to the new tip.
    */
  def endMove(from: ArrowEnd, to: ArrowEnd): Vec => Vec =
    val angle = math.atan2(to.direction.y, to.direction.x) - math.atan2(from.direction.y, from.direction.x)
    val (cos, sin) = (math.cos(angle), math.sin(angle))
    p =>
      val r = p - from.tip
      to.tip + Vec(r.x * cos - r.y * sin, r.x * sin + r.y * cos)

  /** The point at half of the length of the path. The length comes from 16 samples on each segment. */
  def pointAtHalf(segments: Vector[Cubic]): Vec =
    val samples = segments.flatMap(s => (0 to 16).map(i => s.at(i / 16.0)))
    if samples.isEmpty then Vec(0, 0)
    else
      val parts  = samples.zip(samples.tail).map((a, b) => (a, b, (b - a).length))
      val half   = parts.map(_._3).sum / 2
      val before = parts.scanLeft(0.0)(_ + _._3)
      parts.indices
        .find(i => before(i + 1) >= half)
        .fold(samples.last): i =>
          val (a, b, l) = parts(i)
          if l <= 1e-12 then a else a + (b - a) * ((half - before(i)) / l)

  /** The index in `bends` for a new bend at `p`: the new bend goes between the two points of the part of the polyline
    * `start, bends, end` that is nearest to `p`.
    */
  def insertionIndex(start: Vec, bends: Vector[Vec], end: Vec, p: Vec): Int =
    val points = (start +: bends) :+ end
    def distance(a: Vec, b: Vec): Double =
      val ab = b - a
      val l2 = ab.dot(ab)
      val t  = if l2 <= 1e-12 then 0.0 else ((p - a).dot(ab) / l2).max(0).min(1)
      (p - (a + ab * t)).length
    points.indices.init.minBy(i => distance(points(i), points(i + 1)))

  /** The SVG path data of the segments, with absolute commands: `M x,y C x,y x,y x,y ...`. */
  def pathData(segments: Vector[Cubic]): String =
    segments.headOption.fold(""): first =>
      def pt(v: Vec): String = s"${FreeLayout.number(round3(v.x))},${FreeLayout.number(round3(v.y))}"
      s"M${pt(first.p0)}C" + segments.map(s => s"${pt(s.c1)} ${pt(s.c2)} ${pt(s.p1)}").mkString(" ")

  private def round3(v: Double): Double = math.round(v * 1000).toDouble / 1000

  private val Number = """-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""".r

  /** The points of the absolute path data `d` of Graphviz: each pair of numbers, in order. Graphviz writes a node
    * shape or a decoration with `M` and `C` commands, so each pair is a point (an end or a control point).
    */
  def pathPoints(d: String): Vector[Vec] =
    // A command letter separates two numbers, as in `-108C42`: it becomes a space. A removal of the letter joins
    // the two numbers into one.
    val values = Number.findAllIn(Option(d).fold("")(_.map(ch => if ch.isLetter then ' ' else ch))).flatMap(_.toDoubleOption).toVector
    values.grouped(2).collect { case Vector(x, y) => Vec(x, y) }.toVector

  /** The cubic segments of the path data `d` of a Graphviz arrow: one `M` point, and then one `C` command with
    * groups of three points. Other path data gives no segment.
    */
  def parsePath(d: String): Vector[Cubic] =
    val trimmed = d.trim
    val cIndex  = trimmed.indexOf('C')
    if !trimmed.startsWith("M") || cIndex < 0 || trimmed.exists(ch => "LlHhVvSsQqTtAaZzmc".contains(ch)) then Vector.empty
    else
      val start = Number.findAllIn(trimmed.substring(1, cIndex)).map(_.toDouble).toVector
      val rest  = Number.findAllIn(trimmed.substring(cIndex + 1)).map(_.toDouble).toVector
      if start.size != 2 || rest.isEmpty || rest.size % 6 != 0 then Vector.empty
      else
        val pts = rest.grouped(2).map(v => Vec(v(0), v(1))).toVector.grouped(3).toVector
        pts
          .scanLeft(Option.empty[Cubic]): (before, triple) =>
            val p0 = before.fold(Vec(start(0), start(1)))(_.p1)
            Some(Cubic(p0, triple(0), triple(1), triple(2)))
          .flatten

end BendPath
