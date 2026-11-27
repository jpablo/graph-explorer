package org.jpablo.graphexplorer.gxcore.layout

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.layout.BendPath.{Cubic, NodeShape, Vec}

/** The path of an arrow with bend points (ADR 0004, part 4).
  *
  * The path is a smooth curve through the bend points. It goes from the boundary of the source node to the boundary
  * of the target node, and the arrowhead is at the target.
  */
class BendPathSpec extends FunSuite:

  private val eps = 1e-6

  private val box     = NodeShape.box(Vec(0, 0), 54, 36)
  private val ellipse = NodeShape.Ellipse(Vec(0, 0), 27, 18)
  private val source = NodeShape.box(Vec(0, 0), 54, 36)
  private val target = NodeShape.Ellipse(Vec(300, 0), 27, 18)
  private val bends  = Vector(Vec(100, 90), Vec(200, 90))
  private val points = Vector(Vec(0, 0), Vec(100, 50), Vec(200, -20), Vec(260, 80))

  private def near(obtained: Vec, expected: Vec, what: String, tolerance: Double = eps): Unit =
    assert(
      math.abs(obtained.x - expected.x) <= tolerance && math.abs(obtained.y - expected.y) <= tolerance,
      s"$what: obtained $obtained, expected $expected"
    )

  private def nearValue(obtained: Double, expected: Double, what: String, tolerance: Double = eps): Unit =
    assert(math.abs(obtained - expected) <= tolerance, s"$what: obtained $obtained, expected $expected")

  /** The direction of `v`, with no length. */
  private def parallel(a: Vec, b: Vec): Boolean =
    math.abs(a.x * b.y - a.y * b.x) <= 1e-6 * (a.length * b.length max 1) && (a.x * b.x + a.y * b.y) > 0

  // ------------------------------------------------------------ Catmull-Rom to Bézier

  test("the curve has one cubic segment between each two points, and it goes through each point in order"):
    val segments = BendPath.catmullRom(points)
    assertEquals(segments.size, points.size - 1)
    segments.zipWithIndex.foreach: (s, i) =>
      near(s.p0, points(i), s"the start of segment $i")
      near(s.p1, points(i + 1), s"the end of segment $i")
      near(s.at(0), points(i), s"the point at t = 0 of segment $i")
      near(s.at(1), points(i + 1), s"the point at t = 1 of segment $i")

  test("the curve is smooth at each inner point: the two control points are opposite and of equal length"):
    val segments = BendPath.catmullRom(points)
    segments.zip(segments.tail).foreach: (in, out) =>
      val before = in.p1 - in.c2
      val after  = out.c1 - out.p0
      near(before, after, s"the tangents at ${in.p1}")

  test("the Catmull-Rom control points are a sixth of the chord of the neighbors"):
    val segments = BendPath.catmullRom(points)
    // Inner point 1: the tangent is (P2 - P0) / 2, so the control points are P1 ± (P2 - P0) / 6.
    near(segments(0).c2, points(1) - (points(2) - points(0)) * (1.0 / 6), "c2 of segment 0")
    near(segments(1).c1, points(1) + (points(2) - points(0)) * (1.0 / 6), "c1 of segment 1")

  test("at the two ends, the curve goes straight toward the next point"):
    val segments = BendPath.catmullRom(points)
    assert(parallel(segments.head.c1 - points(0), points(1) - points(0)), s"the start tangent: ${segments.head}")
    assert(parallel(points.last - segments.last.c2, points.last - points(points.size - 2)), s"the end tangent: ${segments.last}")

  test("two points give a straight segment, and one point gives no segment"):
    val line = BendPath.catmullRom(Vector(Vec(0, 0), Vec(30, 60)))
    assertEquals(line.size, 1)
    near(line.head.at(0.5), Vec(15, 30), "the middle of the straight segment")
    assertEquals(BendPath.catmullRom(Vector(Vec(1, 1))), Vector.empty)

  // ------------------------------------------------------------ the boundary of a node

  test("the ray from the center of a box leaves the box on the side that it meets first"):
    near(BendPath.exit(box, Vec(100, 0)), Vec(27, 0), "to the right")
    near(BendPath.exit(box, Vec(0, -100)), Vec(0, -18), "down")
    // The ray at 45 degrees meets the top side (y = 18) before the right side (x = 27).
    near(BendPath.exit(box, Vec(100, 100)), Vec(18, 18), "at 45 degrees")

  test("the ray from the center of an ellipse leaves it on the ellipse"):
    near(BendPath.exit(ellipse, Vec(100, 0)), Vec(27, 0), "to the right")
    near(BendPath.exit(ellipse, Vec(0, 50)), Vec(0, 18), "up")
    val p = BendPath.exit(ellipse, Vec(40, 30))
    nearValue((p.x / 27) * (p.x / 27) + (p.y / 18) * (p.y / 18), 1, "the point is on the ellipse")
    assert(parallel(p, Vec(40, 30)), s"the point is not on the ray: $p")

  test("a node shape away from the origin uses its own center"):
    val moved = NodeShape.box(Vec(100, 200), 54, 36)
    near(moved.center, Vec(100, 200), "the center of the box")
    near(BendPath.exit(moved, Vec(300, 200)), Vec(127, 200), "to the right")

  test("a polygon shape is the polygon of its points, for example a diamond"):
    val diamond = NodeShape.Polygon(Vector(Vec(0, 20), Vec(40, 0), Vec(0, -20), Vec(-40, 0)))
    near(diamond.center, Vec(0, 0), "the center of the diamond")
    // The ray to the right meets the corner (40, 0). The ray at 45 degrees meets the side from (0, 20) to (40, 0).
    near(BendPath.exit(diamond, Vec(100, 0)), Vec(40, 0), "to the corner")
    val p = BendPath.exit(diamond, Vec(100, 100))
    nearValue(p.x, p.y, "on the ray at 45 degrees")
    nearValue(p.x / 40 + p.y / 20, 1, "on the side of the diamond")

  test("the hull of the points of a rounded box is near the box, and an arrow ends on it"):
    // The points of the path of a rounded box of Graphviz, 54 by 36 points: the ends and the control points.
    val points = Vector(
      Vec(42, -108), Vec(42, -108), Vec(12, -108), Vec(12, -108), Vec(6, -108), Vec(0, -102), Vec(0, -96), Vec(0, -96),
      Vec(0, -84), Vec(0, -84), Vec(0, -78), Vec(6, -72), Vec(12, -72), Vec(12, -72), Vec(42, -72), Vec(42, -72),
      Vec(48, -72), Vec(54, -78), Vec(54, -84), Vec(54, -84), Vec(54, -96), Vec(54, -96), Vec(54, -102), Vec(48, -108),
      Vec(42, -108)
    )
    val hull = NodeShape.hull(points)
    assert(hull.isDefined, "the points gave no hull")
    near(hull.get.center, Vec(27, -90), "the center of the hull")
    near(BendPath.exit(hull.get, Vec(27, 0)), Vec(27, -72), "down, on the side of the box")
    near(BendPath.exit(hull.get, Vec(100, -90)), Vec(54, -90), "to the right, on the side of the box")
    // The ray toward the corner (54, -72) of the box meets the cut corner from (48, -72) to (54, -78), as the curve
    // turns before the corner.
    near(BendPath.exit(hull.get, Vec(81, -54)), Vec(50.4, -74.4), "toward the corner")
    assert(NodeShape.hull(Vector(Vec(0, 0), Vec(1, 1), Vec(2, 2))).isEmpty, "points on one line gave a hull")
    assert(NodeShape.hull(Vector(Vec(0, 0), Vec(1, 1))).isEmpty, "two points gave a hull")

  test("the shape of a rounded box from the exact path data of Graphviz is the box, and an arrow leaves it on its side"):
    // `shape=box, style="filled,rounded"`, as Graphviz writes it: one `M`, then one `C` with no space before it.
    val d     = "M42,-108C42,-108 12,-108 12,-108 6,-108 0,-102 0,-96 0,-96 0,-84 0,-84 0,-78 6,-72 12,-72 12,-72 42,-72 42,-72 48,-72 54,-78 54,-84 54,-84 54,-96 54,-96 54,-102 48,-108 42,-108"
    val shape = NodeShape.ofPath(d)
    assert(shape.isDefined, "the path data gave no shape")
    val ps = shape.get match
      case NodeShape.Polygon(points) => points
      case other                     => fail(s"the shape is not a polygon: $other")
    nearValue(ps.map(_.x).min, 0, "left")
    nearValue(ps.map(_.x).max, 54, "right")
    nearValue(ps.map(_.y).min, -108, "top")
    nearValue(ps.map(_.y).max, -72, "bottom")
    near(BendPath.exit(shape.get, Vec(27, 0)), Vec(27, -72), "down, on the bottom side")
    near(BendPath.exit(shape.get, Vec(27, -300)), Vec(27, -108), "up, on the top side")
    // The same box, larger and away from the origin.
    val large = NodeShape.ofPath(
      "M317.5,-252.5C317.5,-252.5 154.25,-252.5 154.25,-252.5 148.25,-252.5 142.25,-246.5 142.25,-240.5 142.25,-240.5 142.25,-204.5 142.25,-204.5 142.25,-198.5 148.25,-192.5 154.25,-192.5 154.25,-192.5 317.5,-192.5 317.5,-192.5 323.5,-192.5 329.5,-198.5 329.5,-204.5 329.5,-204.5 329.5,-240.5 329.5,-240.5 329.5,-246.5 323.5,-252.5 317.5,-252.5"
    )
    assert(large.isDefined, "the large path gave no shape")
    near(large.get.center, Vec(235.875, -222.5), "the center of the large box")
    near(BendPath.exit(large.get, Vec(500, -222.5)), Vec(329.5, -222.5), "to the right of the large box")

  test("the points of path data: a command letter separates two numbers"):
    assertEquals(BendPath.pathPoints("M1,-2C3,-4 5,-6 7,-8"), Vector(Vec(1, -2), Vec(3, -4), Vec(5, -6), Vec(7, -8)))
    assertEquals(BendPath.pathPoints("M1 2L3 4Z"), Vector(Vec(1, 2), Vec(3, 4)))
    assertEquals(BendPath.pathPoints(null), Vector.empty)

  // ------------------------------------------------------------ the route of an arrow

  test("a route goes from the source boundary, through the bends, to the base of the arrowhead"):
    val route = BendPath.route(source, target, bends, headLength = 10, tailLength = 0)
    val segs  = route.segments
    assertEquals(segs.size, bends.size + 1)
    // The tail has no decoration: the path starts on the boundary of the source, toward the first bend.
    near(segs.head.p0, BendPath.exit(source, bends.head), "the start of the path")
    near(route.tail.tip, segs.head.p0, "the tail tip with no decoration")
    near(segs(0).p1, bends(0), "the first bend")
    near(segs(1).p1, bends(1), "the second bend")
    // The arrowhead tip is on the boundary of the target, on the ray from the center to the last bend.
    near(route.head.tip, BendPath.exit(target, bends.last), "the arrowhead tip")
    nearValue(math.hypot(route.head.tip.x - segs.last.p1.x, route.head.tip.y - segs.last.p1.y), 10, "the arrowhead length")
    // The arrowhead points along the last part of the curve, into the target.
    assert(parallel(route.head.direction, segs.last.p1 - segs.last.c2), s"the arrowhead direction: ${route.head}")
    assert(parallel(route.head.direction, target.center - bends.last), s"the arrowhead does not point into the target: ${route.head}")
    nearValue(route.head.direction.length, 1, "the direction is a unit vector")

  test("a tail decoration shortens the start of the path, as the arrowhead does at the end"):
    val route = BendPath.route(source, target, bends, headLength = 10, tailLength = 8)
    near(route.tail.tip, BendPath.exit(source, bends.head), "the tail tip")
    nearValue(math.hypot(route.tail.tip.x - route.segments.head.p0.x, route.tail.tip.y - route.segments.head.p0.y), 8, "the tail length")
    assert(parallel(route.tail.direction, source.center - bends.head), s"the tail does not point into the source: ${route.tail}")

  test("the arrowhead angle is the angle of its direction"):
    nearValue(BendPath.angleDegrees(Vec(1, 0)), 0, "to the right")
    nearValue(BendPath.angleDegrees(Vec(0, 1)), 90, "along y")
    nearValue(BendPath.angleDegrees(Vec(-1, 0)), 180, "to the left")
    nearValue(BendPath.angleDegrees(Vec(1, -1)), -45, "at -45 degrees")

  test("the move of an arrowhead turns its shape from the old tip and direction to the new ones"):
    // A Graphviz arrowhead to the right: the tip at (10, 0), the base corners at (0, ±3.5).
    val move = BendPath.endMove(BendPath.ArrowEnd(Vec(10, 0), Vec(1, 0)), BendPath.ArrowEnd(Vec(0, 10), Vec(0, 1)))
    near(move(Vec(10, 0)), Vec(0, 10), "the tip")
    near(move(Vec(0, 3.5)), Vec(-3.5, 0), "a base corner")
    near(move(Vec(0, -3.5)), Vec(3.5, 0), "the other base corner")

  // ------------------------------------------------------------ the other parts

  test("the point at half of the length of a path"):
    val line = BendPath.catmullRom(Vector(Vec(0, 0), Vec(100, 0), Vec(100, 100)))
    // A polyline of two equal parts has its middle at the corner. The curve is near it.
    near(BendPath.pointAtHalf(line), Vec(100, 0), "the middle", tolerance = 12)
    near(BendPath.pointAtHalf(BendPath.catmullRom(Vector(Vec(0, 0), Vec(80, 40)))), Vec(40, 20), "the middle of a line", tolerance = 1e-3)

  test("a new bend goes between the two points of the nearest part of the arrow"):
    val start = Vec(0, 0)
    val end   = Vec(100, 100)
    val bend  = Vector(Vec(100, 0))
    assertEquals(BendPath.insertionIndex(start, bend, end, Vec(50, 2)), 0)
    assertEquals(BendPath.insertionIndex(start, bend, end, Vec(98, 50)), 1)
    assertEquals(BendPath.insertionIndex(start, Vector.empty, end, Vec(10, 30)), 0)

  test("the path data of a Graphviz edge reads as cubic segments, and a write gives the same segments"):
    val d    = "M27,-71.7C27,-63.98 27,-54.71 27,-46.11"
    val segs = BendPath.parsePath(d)
    assertEquals(segs, Vector(Cubic(Vec(27, -71.7), Vec(27, -63.98), Vec(27, -54.71), Vec(27, -46.11))))
    assertEquals(BendPath.parsePath(BendPath.pathData(segs)), segs)
    assertEquals(BendPath.parsePath("M1,2C3,4 5,6 7,8 9,10 11,12 13,14").size, 2)
    assertEquals(BendPath.parsePath("not a path"), Vector.empty)

end BendPathSpec
