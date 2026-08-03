package org.jpablo.graphexplorer.viewer.layout3d

import org.jpablo.graphexplorer.viewer.models.NodeId

import scala.collection.mutable

/** Hierarchical layout in three dimensions: DOT-style ranks become horizontal
  * planes stacked top-down along Y, and each rank's nodes spread out on their
  * plane, pulled sideways toward their neighbors in adjacent ranks (the
  * barycenter heuristic, continuous instead of orderings) and pushed apart so
  * they never overlap.
  *
  * A direct layout, not a simulation: targets are computed in closed form from
  * the graph, and `step` tweens the current positions toward them — which is
  * what makes switching FROM another layout an animated morph for free.
  *
  * Ranking is longest-path over a DFS-acyclic subgraph (back edges dropped for
  * ranking only, exactly like dot's acyclic pass in spirit). Everything is
  * deterministic in node/edge declaration order.
  */
object LayeredLayout3D extends Layout3D:

  val id    = "layers"
  val label = "Layers"

  override val knobs: List[Knob3D] = List(
    Knob3D("layerGap", "Layer gap", 0.6, 4.0, 0.05, 1.6),
    Knob3D("k", "Spacing", 0.4, 3.0, 0.05, 1.0)
  )

  def paramsFrom(values: Map[String, Double]): Params =
    Params(
      k = values.getOrElse("k", defaultParams.k),
      layerGap = values.getOrElse("layerGap", defaultParams.layerGap)
    )

  override def withKnobs(values: Map[String, Double]): Layout3D =
    Configured(paramsFrom(values))

  def reheat(state: LayoutState3D): LayoutState3D = reheat(state, defaultParams)

  /** New parameters mean new closed-form targets; distance to them is the new
    * remaining animation.
    */
  def reheat(state: LayoutState3D, params: Params): LayoutState3D =
    val t = targets(state.graph, params)
    state.copy(targets = t, temperature = maxDistanceToTargets(state.positions, t))

  private final class Configured(params: Params) extends Layout3D:
    def id    = LayeredLayout3D.id
    def label = LayeredLayout3D.label
    override def knobs = LayeredLayout3D.knobs
    override def withKnobs(values: Map[String, Double]) = LayeredLayout3D.withKnobs(values)
    def initial(graph: LayoutGraph)                     = LayeredLayout3D.initial(graph, params)
    def sync(state: LayoutState3D, newGraph: LayoutGraph) = LayeredLayout3D.sync(state, newGraph, params)
    def step(state: LayoutState3D)                      = LayeredLayout3D.step(state, params)
    def reheat(state: LayoutState3D)                    = LayeredLayout3D.reheat(state, params)

  case class Params(
      /** Within-layer separation unit; matches ForceLayout3D's k scale. */
      k: Double = 1.0,
      /** Vertical distance between ranks, in k units. */
      layerGap: Double = 1.6,
      /** Barycenter relaxation passes over the whole graph. */
      sweeps: Int = 40,
      /** Two nodes on one plane are pushed apart to at least this (in k). */
      minSepFactor: Double = 0.9,
      /** Fraction of the remaining distance a tween step closes. */
      tweenRate: Double = 0.18,
      /** Done when the farthest node is within this (in k) of its target. */
      settleEpsFactor: Double = 0.002
  )

  val defaultParams: Params = Params()

  private val goldenAngle = math.Pi * (3.0 - math.sqrt(5.0))

  def initial(graph: LayoutGraph): LayoutState3D = initial(graph, defaultParams)

  def initial(graph: LayoutGraph, params: Params): LayoutState3D =
    val t = targets(graph, params)
    LayoutState3D(id, graph, positions = t, temperature = 0, iteration = 0, targets = t)

  def sync(state: LayoutState3D, newGraph: LayoutGraph): LayoutState3D =
    sync(state, newGraph, defaultParams)

  /** Surviving nodes tween from where they are; new nodes appear at their
    * target directly (there is no meaningful "previous" place for them).
    */
  def sync(state: LayoutState3D, newGraph: LayoutGraph, params: Params): LayoutState3D =
    if state.graph == newGraph && state.algoId == id then state
    else
      val t = targets(newGraph, params)
      val positions =
        newGraph.nodes.iterator
          .map(nodeId => nodeId -> state.positions.getOrElse(nodeId, t(nodeId)))
          .toMap
      val temperature = maxDistanceToTargets(positions, t)
      if temperature <= params.k * params.settleEpsFactor then
        LayoutState3D(id, newGraph, t, 0, 0, t)
      else
        LayoutState3D(id, newGraph, positions, temperature, 0, t)

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
      if remaining <= params.k * params.settleEpsFactor then
        state.copy(positions = state.targets, temperature = 0, iteration = state.iteration + 1)
      else
        state.copy(positions = moved, temperature = remaining, iteration = state.iteration + 1)

  private def maxDistanceToTargets(positions: Map[NodeId, Vec3], targets: Map[NodeId, Vec3]): Double =
    if targets.isEmpty then 0.0
    else
      targets.iterator
        .map((nodeId, t) => positions.get(nodeId).map(p => (t - p).length).getOrElse(0.0))
        .maxOption
        .getOrElse(0.0)

  // ------------- target computation -------------

  /** Longest-path ranks over the acyclic subgraph: rank(v) = one more than the
    * deepest kept in-edge. Kahn's algorithm, seeded and propagated in
    * declaration order for determinism.
    */
  def ranks(graph: LayoutGraph): Map[NodeId, Int] =
    val nodes = graph.nodes
    val index = nodes.iterator.zipWithIndex.toMap
    val keep  = acyclicEdges(graph, index)

    val outEdges = Array.fill(nodes.size)(Vector.empty[Int])
    val indegree = Array.fill(nodes.size)(0)
    for ei <- graph.edges.indices if keep(ei) do
      val (s, t) = graph.edges(ei)
      outEdges(index(s)) = outEdges(index(s)) :+ ei
      indegree(index(t)) += 1

    val rank  = Array.fill(nodes.size)(0)
    val queue = mutable.Queue.from(nodes.indices.filter(indegree(_) == 0))
    while queue.nonEmpty do
      val u = queue.dequeue()
      for ei <- outEdges(u) do
        val v = index(graph.edges(ei)._2)
        rank(v) = math.max(rank(v), rank(u) + 1)
        indegree(v) -= 1
        if indegree(v) == 0 then queue.enqueue(v)

    nodes.iterator.zipWithIndex.map((nodeId, i) => nodeId -> rank(i)).toMap

  /** Which edges the RANKING sees: self-loops and DFS back edges are dropped
    * (only for ranking — they still draw, and the barycenter pass still feels
    * them). Iterative DFS in declaration order, so which edge of a cycle
    * breaks is stable.
    */
  private def acyclicEdges(graph: LayoutGraph, index: Map[NodeId, Int]): Array[Boolean] =
    val keep     = Array.fill(graph.edges.length)(true)
    val outEdges = Array.fill(graph.nodes.size)(Vector.empty[Int])
    for ei <- graph.edges.indices do
      val (s, t) = graph.edges(ei)
      if s == t then keep(ei) = false
      else outEdges(index(s)) = outEdges(index(s)) :+ ei

    val White = 0; val Gray = 1; val Black = 2
    val color = Array.fill(graph.nodes.size)(White)
    val stack = mutable.Stack.empty[(Int, Iterator[Int])]
    for root <- graph.nodes.indices if color(root) == White do
      color(root) = Gray
      stack.push((root, outEdges(root).iterator))
      while stack.nonEmpty do
        val (u, it) = stack.top
        if it.hasNext then
          val ei = it.next()
          val v  = index(graph.edges(ei)._2)
          if color(v) == Gray then keep(ei) = false // back edge: breaking it breaks the cycle
          else if color(v) == White then
            color(v) = Gray
            stack.push((v, outEdges(v).iterator))
        else
          stack.pop()
          color(u) = Black
    keep

  /** The closed-form result: rank planes along Y (rank 0 on top, vertically
    * centered), and within each plane a sunflower-disc start refined by
    * barycenter pulls toward adjacent-rank neighbors plus pairwise separation.
    */
  def targets(graph: LayoutGraph, params: Params = defaultParams): Map[NodeId, Vec3] =
    val nodes = graph.nodes
    if nodes.isEmpty then Map.empty
    else
      val index   = nodes.iterator.zipWithIndex.toMap
      val rankOf  = ranks(graph)
      val maxRank = rankOf.valuesIterator.max
      val gap     = params.layerGap * params.k

      val layers: Map[Int, Vector[Int]] =
        nodes.indices.toVector.groupBy(i => rankOf(nodes(i)))

      val xs = Array.fill(nodes.size)(0.0)
      val zs = Array.fill(nodes.size)(0.0)
      for (_, members) <- layers; (i, j) <- members.zipWithIndex do
        val radius = params.k * 0.7 * math.sqrt(j.toDouble)
        val theta  = j * goldenAngle
        xs(i) = math.cos(theta) * radius
        zs(i) = math.sin(theta) * radius

      // Undirected neighbor lists (self-loops excluded): the sideways pull
      // does not care which way an edge points, only who should sit nearby.
      val neighbors = Array.fill(nodes.size)(Vector.empty[Int])
      for (s, t) <- graph.edges if s != t do
        neighbors(index(s)) = neighbors(index(s)) :+ index(t)
        neighbors(index(t)) = neighbors(index(t)) :+ index(s)

      val minSep = params.k * params.minSepFactor
      for _ <- 1 to params.sweeps do
        // Pull toward the neighbors' barycenter (Gauss–Seidel: reads see this
        // sweep's earlier writes, which converges faster and stays ordered).
        for i <- nodes.indices if neighbors(i).nonEmpty do
          val ns = neighbors(i)
          val bx = ns.map(xs).sum / ns.size
          val bz = ns.map(zs).sum / ns.size
          xs(i) = (xs(i) + bx) * 0.5
          zs(i) = (zs(i) + bz) * 0.5
        // Separate overlapping nodes within each plane.
        for r <- 0 to maxRank; members = layers.getOrElse(r, Vector.empty) do
          for a <- members.indices; b <- a + 1 until members.size do
            val i  = members(a)
            val j  = members(b)
            val dx = xs(j) - xs(i)
            val dz = zs(j) - zs(i)
            val d  = math.hypot(dx, dz)
            if d < minSep then
              // Coincident nodes get a deterministic direction from their index.
              val (ux, uz) =
                if d < 1e-9 then (math.cos(j * goldenAngle), math.sin(j * goldenAngle))
                else (dx / d, dz / d)
              val push = (minSep - d) / 2
              xs(i) -= ux * push; zs(i) -= uz * push
              xs(j) += ux * push; zs(j) += uz * push

      // Center the drawing on the origin so gravity-free ranks still orbit
      // nicely and the camera fit sees a balanced radius.
      val cx = xs.sum / nodes.size
      val cz = zs.sum / nodes.size
      nodes.iterator.zipWithIndex
        .map: (nodeId, i) =>
          val y = (maxRank * 0.5 - rankOf(nodeId)) * gap
          nodeId -> Vec3(xs(i) - cx, y, zs(i) - cz)
        .toMap
