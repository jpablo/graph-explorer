package org.jpablo.graphexplorer.viewer.layout3d

import org.jpablo.graphexplorer.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{getEdgePos, ArrowPosition, SimpleGraph}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.NodeId
import upickle.default.read

import scala.util.Try

/** The dot engine's flat drawing, as plain data for a 3D layout: node centers
  * and per-edge spline paths, both in dot's own coordinate system (points,
  * y-up, origin at the drawing's lower-left). Coordinates stay in points on
  * purpose — the layout owns the mapping into world units.
  *
  * `edgePaths` is parallel to the arrow iteration order of the ViewerGraph it
  * was built from (the same order the renderer and LayoutGraph use). Every
  * path has exactly [[PlanarHints.SamplesPerEdge]] points, endpoints included,
  * resampled uniformly by arc length from dot's cubic splines.
  */
case class PlanarHints(
    positions: Map[NodeId, (Double, Double)],
    edgePaths: Vector[Vector[(Double, Double)]],
    /** Node box (width, height) in POINTS — the geometry dot clipped the
      * splines against. A renderer that sizes its nodes to these makes the
      * arrows meet the shapes exactly as in the flat drawing.
      */
    sizes: Map[NodeId, (Double, Double)] = Map.empty
) derives CanEqual

object PlanarHints:

  /** Path resolution. Enough to read dot's gentle spline curvature plus a
    * depth bow; every edge gets the same count so the renderer can allocate
    * segment meshes uniformly.
    */
  val SamplesPerEdge = 9

  /** Run the (pure, synchronous) dot engine over the graph and extract the
    * flat drawing. None when the engine fails or emits unparseable JSON —
    * callers fall back to a hint-less layout.
    */
  def fromViewerGraph(g: ViewerGraph): Option[PlanarHints] =
    val dot    = ViewerGraph.viewerGraphToText(g, omitInternal = false)
    val result = Graphviz.renderFormats(dot, Seq("json0"))
    for
      json <- result.output.get("json0")
      if result.status == "success"
      sg   <- Try(read[SimpleGraph](json)).toOption
    yield fromSimpleGraph(g, sg)

  private def fromSimpleGraph(g: ViewerGraph, sg: SimpleGraph): PlanarHints =
    val positions: Map[NodeId, (Double, Double)] =
      sg.nodes.iterator
        .flatMap(n => n.pos.flatMap(parsePoint).map(p => NodeId(n.name) -> p))
        .toMap

    // json0 emits width/height in INCHES; everything else here is points.
    val sizes: Map[NodeId, (Double, Double)] =
      sg.nodes.iterator
        .flatMap: node =>
          for
            w <- node.width.flatMap(s => Try(s.toDouble).toOption)
            h <- node.height.flatMap(s => Try(s.toDouble).toOption)
          yield NodeId(node.name) -> (w * 72.0, h * 72.0)
        .toMap

    val edgePos = getEdgePos(sg)

    // Same iteration as Scene3D's `g.arrows.values` — the paths must stay
    // parallel to LayoutGraph.edges.
    val paths =
      g.arrows.toVector.map: (arrowId, arrow) =>
        val found = edgePos
          .get(arrowId.value)
          .orElse(edgePos.get(s"${arrow.source.value}->${arrow.target.value}"))
        found.map(samplePath) match
          case Some(pts) => pts
          case None =>
            // No spline (engine dropped the edge, id mismatch): a straight
            // chord between node centers keeps the vectors parallel.
            (positions.get(arrow.source), positions.get(arrow.target)) match
              case (Some(a), Some(b)) =>
                Vector.tabulate(SamplesPerEdge): j =>
                  val t = j.toDouble / (SamplesPerEdge - 1)
                  (a._1 + (b._1 - a._1) * t, a._2 + (b._2 - a._2) * t)
              case _ => Vector.empty

    PlanarHints(positions, paths, sizes)

  private def parsePoint(s: String): Option[(Double, Double)] =
    // Node pos may carry a trailing "!" (pinned); tolerate it.
    s.stripSuffix("!").split(",") match
      case Array(x, y) => Try((x.toDouble, y.toDouble)).toOption
      case _           => None

  /** dot's `pos` spline as a fixed-size polyline: the control points form a
    * chain of cubic Béziers (3k+1 points); the separate `e` point is the
    * arrowhead TIP beyond the last spline point and becomes the path's final
    * sample. Densely evaluate the chain, then resample uniformly by arc
    * length so sample t is a stable parameterization for the depth bow.
    */
  private def samplePath(ap: ArrowPosition): Vector[(Double, Double)] =
    val spline = (ap.startPoint :: ap.controlPoints).map(p => (p.x, p.y)).toVector
    val dense =
      if spline.size >= 4 && spline.size % 3 == 1 then
        val perSegment = 8
        val segments   = (spline.size - 1) / 3
        val buf        = Vector.newBuilder[(Double, Double)]
        for s <- 0 until segments do
          val p0 = spline(s * 3); val p1 = spline(s * 3 + 1)
          val p2 = spline(s * 3 + 2); val p3 = spline(s * 3 + 3)
          val from = if s == 0 then 0 else 1 // skip the shared knot after the first segment
          for j <- from to perSegment do
            val t = j.toDouble / perSegment
            buf += cubic(p0, p1, p2, p3, t)
        buf.result()
      else spline // malformed count: treat the points as a polyline
    resample(dense :+ ((ap.endPoint.x, ap.endPoint.y)), SamplesPerEdge)

  private def cubic(
      p0: (Double, Double),
      p1: (Double, Double),
      p2: (Double, Double),
      p3: (Double, Double),
      t:  Double
  ): (Double, Double) =
    val u = 1 - t
    val (a, b, c, d) = (u * u * u, 3 * u * u * t, 3 * u * t * t, t * t * t)
    (a * p0._1 + b * p1._1 + c * p2._1 + d * p3._1, a * p0._2 + b * p1._2 + c * p2._2 + d * p3._2)

  /** Uniform-by-arc-length resampling to exactly `n` points (endpoints kept). */
  private def resample(pts: Vector[(Double, Double)], n: Int): Vector[(Double, Double)] =
    if pts.size < 2 then Vector.fill(n)(pts.headOption.getOrElse((0.0, 0.0)))
    else
      val cum = pts
        .sliding(2)
        .scanLeft(0.0):
          case (acc, Seq(a, b)) => acc + math.hypot(b._1 - a._1, b._2 - a._2)
          case (acc, _)         => acc
        .toVector
      val total = cum.last
      if total <= 1e-9 then Vector.fill(n)(pts.head)
      else
        Vector.tabulate(n): j =>
          val target = total * j / (n - 1)
          val i      = cum.lastIndexWhere(_ <= target).max(0).min(pts.size - 2)
          val span   = cum(i + 1) - cum(i)
          val t      = if span <= 1e-9 then 0.0 else (target - cum(i)) / span
          val (a, b) = (pts(i), pts(i + 1))
          (a._1 + (b._1 - a._1) * t, a._2 + (b._2 - a._2) * t)
