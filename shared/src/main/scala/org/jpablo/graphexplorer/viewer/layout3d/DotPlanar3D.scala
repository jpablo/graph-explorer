package org.jpablo.graphexplorer.viewer.layout3d

import org.jpablo.graphexplorer.viewer.models.NodeId

/** The flat dot drawing, promoted to 2.5D: nodes sit exactly where the dot
  * engine put them, in the z = 0 plane, and edges trace dot's own splines —
  * except that edges use the depth axis to stay out of each other's way.
  * Every edge gets a signed LEVEL: 0 stays in the plane, +1 bows toward the
  * viewer, −1 away, ±2 further out. Levels are assigned so that edges whose
  * chords cross in the flat drawing never share one — the crossing that
  * needed an over/under guess in 2D becomes actual depth. Everything else is
  * deliberately conservative: non-crossing edges keep level 0, and with the
  * Depth knob at 0 the layout IS the flat dot diagram, splines included.
  *
  * A direct layout in the LayeredLayout3D mold: closed-form targets, tweened
  * positions, so switching to it morphs. The flat drawing arrives as
  * [[PlanarHints]] on the LayoutGraph (the caller runs the dot engine);
  * without hints it degrades to the layered targets with straight edges.
  */
object DotPlanar3D extends Layout3D:

  val id    = "dotplanar"
  val label = "Flat (dot)"

  override val knobs: List[Knob3D] = List(
    Knob3D("depth", "Depth", 0.0, 1.2, 0.05, 0.35)
  )

  override def wantsPlanarHints: Boolean = true

  /** dot points → world units: 1 inch = 1 unit, so dot's default node (0.5in
    * tall) lands close to the renderer's pill height. Public — the renderer
    * uses the same factor to size node sprites from the hints' point sizes.
    */
  val PtToWorld: Double = 1.0 / 72.0

  case class Params(
      /** Peak out-of-plane bow of a level-±1 edge, in world units. */
      depth: Double = 0.35,
      /** See [[PtToWorld]]. */
      ptToWorld: Double = PtToWorld,
      /** Fraction of the remaining distance a tween step closes. */
      tweenRate: Double = 0.18,
      /** Done when the farthest node is within this of its target. */
      settleEps: Double = 0.002
  )

  val defaultParams: Params = Params()

  def paramsFrom(values: Map[String, Double]): Params =
    Params(depth = values.getOrElse("depth", defaultParams.depth))

  override def withKnobs(values: Map[String, Double]): Layout3D =
    Configured(paramsFrom(values))

  private final class Configured(params: Params) extends Layout3D:
    def id    = DotPlanar3D.id
    def label = DotPlanar3D.label
    override def knobs                                  = DotPlanar3D.knobs
    override def wantsPlanarHints                       = true
    override def withKnobs(values: Map[String, Double]) = DotPlanar3D.withKnobs(values)
    def initial(graph: LayoutGraph)                     = DotPlanar3D.initial(graph, params)
    def sync(state: LayoutState3D, newGraph: LayoutGraph) = DotPlanar3D.sync(state, newGraph, params)
    def step(state: LayoutState3D)                      = DotPlanar3D.step(state, params)
    def reheat(state: LayoutState3D)                    = DotPlanar3D.reheat(state, params)

  def initial(graph: LayoutGraph): LayoutState3D = initial(graph, defaultParams)

  def initial(graph: LayoutGraph, params: Params): LayoutState3D =
    val t = targets(graph, params)
    LayoutState3D(id, graph, positions = t, temperature = 0, iteration = 0, targets = t, edgeOffsets = offsets(graph, params))

  def sync(state: LayoutState3D, newGraph: LayoutGraph): LayoutState3D =
    sync(state, newGraph, defaultParams)

  def sync(state: LayoutState3D, newGraph: LayoutGraph, params: Params): LayoutState3D =
    if state.graph == newGraph && state.algoId == id then state
    else
      val t = targets(newGraph, params)
      val positions =
        newGraph.nodes.iterator
          .map(nodeId => nodeId -> state.positions.getOrElse(nodeId, t(nodeId)))
          .toMap
      val off         = offsets(newGraph, params)
      val temperature = maxDistanceToTargets(positions, t)
      if temperature <= params.settleEps then LayoutState3D(id, newGraph, t, 0, 0, t, edgeOffsets = off)
      else LayoutState3D(id, newGraph, positions, temperature, 0, t, edgeOffsets = off)

  def reheat(state: LayoutState3D): LayoutState3D = reheat(state, defaultParams)

  /** A depth change recomputes the edge bows; targets are knob-independent
    * but recomputed for uniformity. Nodes do not move, so this animates only
    * if a real distance remains.
    */
  def reheat(state: LayoutState3D, params: Params): LayoutState3D =
    val t = targets(state.graph, params)
    state.copy(
      targets = t,
      edgeOffsets = offsets(state.graph, params),
      temperature = maxDistanceToTargets(state.positions, t)
    )

  def step(state: LayoutState3D): LayoutState3D = step(state, defaultParams)

  def step(state: LayoutState3D, params: Params): LayoutState3D =
    if state.done then state
    else
      val moved =
        state.positions.map: (nodeId, p) =>
          if state.pinned.contains(nodeId) then nodeId -> p
          else
            state.targets.get(nodeId) match
              case Some(t) => nodeId -> (p + (t - p) * params.tweenRate)
              case None    => nodeId -> p
      val remaining = maxDistanceToTargets(moved, state.targets)
      if remaining <= params.settleEps then
        state.copy(positions = state.targets, temperature = 0, iteration = state.iteration + 1)
      else state.copy(positions = moved, temperature = remaining, iteration = state.iteration + 1)

  private def maxDistanceToTargets(positions: Map[NodeId, Vec3], targets: Map[NodeId, Vec3]): Double =
    if targets.isEmpty then 0.0
    else
      targets.iterator
        .map((nodeId, t) => positions.get(nodeId).map(p => (t - p).length).getOrElse(0.0))
        .maxOption
        .getOrElse(0.0)

  // ------------- target computation -------------

  /** Node targets: the dot positions scaled to world units and centered on
    * the origin (dot's origin is the drawing's lower-left corner — the camera
    * fit wants a balanced radius). Without hints: the layered targets, so the
    * layout never renders garbage.
    */
  def targets(graph: LayoutGraph, params: Params = defaultParams): Map[NodeId, Vec3] =
    graph.hints match
      case None => LayeredLayout3D.targets(graph)
      case Some(h) =>
        val (cx, cy) = centerOf(h)
        graph.nodes.iterator.map { nodeId =>
          val (x, y) = h.positions.getOrElse(nodeId, (cx, cy))
          nodeId -> Vec3((x - cx) * params.ptToWorld, (y - cy) * params.ptToWorld, 0)
        }.toMap

  /** Signed depth level per edge (parallel to graph.edges). Level 0 = in
    * plane; edges whose CHORDS properly cross must differ. Greedy in edge
    * declaration order, always taking the smallest-magnitude free level from
    * 0, +1, −1, +2, −2… — so a lone crossing costs one edge one level, and a
    * bundle of k mutually-crossing edges fans out symmetrically.
    */
  def crossingLevels(graph: LayoutGraph, hints: PlanarHints): Vector[Int] =
    val chords: Vector[Option[((Double, Double), (Double, Double))]] =
      graph.edges.map: (s, t) =>
        if s == t then None // a self-loop has no chord
        else
          for a <- hints.positions.get(s); b <- hints.positions.get(t)
          yield (a, b)

    val crossing = Array.fill(graph.edges.size)(List.empty[Int])
    for
      i <- graph.edges.indices
      j <- i + 1 until graph.edges.size
      ci <- chords(i)
      cj <- chords(j)
      // Edges sharing an endpoint meet, they don't cross.
      if !sharesEndpoint(graph.edges(i), graph.edges(j))
      if segmentsCross(ci._1, ci._2, cj._1, cj._2)
    do
      crossing(i) = j :: crossing(i)
      crossing(j) = i :: crossing(j)

    // k-th candidate in magnitude order: 0, +1, −1, +2, −2, …
    def levelAt(k: Int): Int = if k == 0 then 0 else if k % 2 == 1 then (k + 1) / 2 else -k / 2
    val levels   = Array.fill(graph.edges.size)(0)
    val assigned = Array.fill(graph.edges.size)(false)
    for i <- graph.edges.indices do
      val taken = crossing(i).filter(assigned).map(levels).toSet
      levels(i) = (0 to graph.edges.size).iterator.map(levelAt).find(!taken.contains(_)).getOrElse(0)
      assigned(i) = true
    levels.toVector

  private def sharesEndpoint(a: (NodeId, NodeId), b: (NodeId, NodeId)): Boolean =
    a._1 == b._1 || a._1 == b._2 || a._2 == b._1 || a._2 == b._2

  /** Proper crossing of open segments (touching at an endpoint is not one). */
  private def segmentsCross(
      p1: (Double, Double),
      p2: (Double, Double),
      p3: (Double, Double),
      p4: (Double, Double)
  ): Boolean =
    def orient(a: (Double, Double), b: (Double, Double), c: (Double, Double)): Double =
      (b._1 - a._1) * (c._2 - a._2) - (b._2 - a._2) * (c._1 - a._1)
    val d1 = orient(p3, p4, p1)
    val d2 = orient(p3, p4, p2)
    val d3 = orient(p1, p2, p3)
    val d4 = orient(p1, p2, p4)
    ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))

  /** Per-edge offsets from the straight chord (see LayoutState3D.edgeOffsets):
    * the in-plane part reproduces dot's spline, the z part is the depth bow —
    * level × depth × sin²(πt). sin² rather than sin: both are zero at the
    * endpoints, but sin's SLOPE peaks there, so the edge shot out of the
    * plane right at the node border and read as detached from any off-axis
    * view (parallax between the out-of-plane stem and the in-plane node).
    * sin² leaves tangent to the plane — the edge hugs the drawing near its
    * nodes and bows only through its middle.
    */
  def offsets(graph: LayoutGraph, params: Params = defaultParams): Vector[Vector[Vec3]] =
    graph.hints match
      case None => Vector.empty
      case Some(h) =>
        val (cx, cy) = centerOf(h)
        val t        = targets(graph, params)
        val levels   = crossingLevels(graph, h)
        graph.edges.zipWithIndex.map: (edge, i) =>
          val (s, e) = edge
          val path   = if i < h.edgePaths.size then h.edgePaths(i) else Vector.empty
          (t.get(s), t.get(e)) match
            case (Some(a), Some(b)) if path.size >= 2 =>
              val n = path.size
              Vector.tabulate(n): j =>
                val tt    = j.toDouble / (n - 1)
                val chord = a + (b - a) * tt
                // Endpoints pinned to EXACTLY zero bow (sin(π) is 1e-16, and
                // an edge must attach to its in-plane node, not hover by ulps).
                val bow =
                  if j == 0 || j == n - 1 then 0.0
                  else
                    val s = math.sin(math.Pi * tt)
                    levels(i) * params.depth * s * s
                val world = Vec3(
                  (path(j)._1 - cx) * params.ptToWorld,
                  (path(j)._2 - cy) * params.ptToWorld,
                  bow
                )
                world - chord
            case _ => Vector.empty

  private def centerOf(h: PlanarHints): (Double, Double) =
    if h.positions.isEmpty then (0.0, 0.0)
    else
      val xs = h.positions.valuesIterator.map(_._1).toVector
      val ys = h.positions.valuesIterator.map(_._2).toVector
      ((xs.min + xs.max) / 2, (ys.min + ys.max) / 2)
