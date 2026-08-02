package org.jpablo.graphexplorer.viewer.layout3d

import org.jpablo.graphexplorer.viewer.models.NodeId

/** The layout's view of a graph: node identity and connectivity, nothing else.
  * Equality on this type is what decides whether a re-emitted `visibleGraph`
  * restarts the simulation — attribute-only edits (colors, labels) produce the
  * same LayoutGraph and must leave a settled layout untouched.
  */
case class LayoutGraph(
    nodes: Vector[NodeId],
    edges: Vector[(NodeId, NodeId)]
) derives CanEqual

/** A simulation snapshot. `step` is pure (state => state) so the caller decides
  * pacing: N steps per animation frame, or `run` to convergence in a test.
  * temperature == 0 means settled; `step` is then the identity.
  */
case class LayoutState3D(
    graph:       LayoutGraph,
    positions:   Map[NodeId, Vec3],
    temperature: Double,
    iteration:   Int
) derives CanEqual:
  def done: Boolean = temperature <= 0

/** Fruchterman–Reingold force layout in three dimensions.
  *
  * Deliberately deterministic: initial placement is a Fibonacci sphere (evenly
  * spread, no RNG), so the same graph always produces the same layout and tests
  * can assert on geometry. All-pairs repulsion is O(n²) per step — fine for the
  * hundreds of nodes the viewer draws; revisit (Barnes–Hut) before pointing it
  * at thousands.
  */
object ForceLayout3D:

  case class Params(
      /** Ideal edge length, in world units. Everything else scales off it. */
      k: Double = 1.0,
      /** Uniform pull toward the origin; keeps disconnected components from
        * drifting apart forever (repulsion alone is unbounded).
        */
      gravity: Double = 0.05,
      /** Starting temperature as a fraction of the layout radius. */
      initialTempFactor: Double = 0.3,
      /** Geometric cooling per step. */
      cooling: Double = 0.96,
      /** Temperature floor as a fraction of k: movements below this are
        * invisible, so the simulation snaps to done instead of asymptoting.
        */
      minTempFactor: Double = 0.002
  )

  val defaultParams: Params = Params()

  /** Radius of the initial placement sphere: volume proportional to node count,
    * so density (and thus equilibrium edge lengths) is size-independent.
    */
  def radiusFor(n: Int, k: Double): Double = k * math.cbrt(n.max(1).toDouble)

  /** The i-th of n points on a unit Fibonacci sphere: evenly spread and
    * deterministic, our replacement for random initial placement.
    */
  def fibonacciSphere(i: Int, n: Int): Vec3 =
    val goldenAngle = math.Pi * (3.0 - math.sqrt(5.0))
    val y           = 1.0 - 2.0 * ((i + 0.5) / n.max(1).toDouble)
    val r           = math.sqrt(math.max(0.0, 1.0 - y * y))
    val theta       = goldenAngle * i
    Vec3(math.cos(theta) * r, y, math.sin(theta) * r)

  def initial(graph: LayoutGraph, params: Params = defaultParams): LayoutState3D =
    val n = graph.nodes.size
    val r = radiusFor(n, params.k)
    val positions =
      graph.nodes.iterator.zipWithIndex
        .map((id, i) => id -> fibonacciSphere(i, n) * r)
        .toMap
    LayoutState3D(graph, positions, r * params.initialTempFactor, 0)

  /** Adopt a new graph while keeping the layout the user is looking at:
    * surviving nodes keep their positions exactly; a new node starts at the
    * barycenter of its already-placed neighbors (nudged off it so the spring
    * force is well-defined), or on the placement sphere when it has none.
    * Reheats to half the initial temperature so the change animates in instead
    * of teleporting the whole drawing.
    *
    * Identity when the topology is unchanged — attribute edits must not wiggle
    * a settled layout.
    */
  def sync(state: LayoutState3D, newGraph: LayoutGraph, params: Params = defaultParams): LayoutState3D =
    if state.graph == newGraph then state
    else
      val n       = newGraph.nodes.size
      val r       = radiusFor(n, params.k)
      val nodeSet = newGraph.nodes.toSet
      val kept    = state.positions.filter((id, _) => nodeSet.contains(id))
      val positions = newGraph.nodes.iterator.zipWithIndex.foldLeft(kept):
        case (acc, (id, i)) =>
          if acc.contains(id) then acc
          else
            val placedNeighbors =
              newGraph.edges.iterator
                .collect:
                  case (s, t) if s == id && acc.contains(t) => acc(t)
                  case (s, t) if t == id && acc.contains(s) => acc(s)
                .toVector
            val pos =
              if placedNeighbors.isEmpty then fibonacciSphere(i, n) * r
              else
                val barycenter = placedNeighbors.reduce(_ + _) * (1.0 / placedNeighbors.size)
                barycenter + fibonacciSphere(i, n.max(2)) * (params.k * 0.5)
            acc.updated(id, pos)
      LayoutState3D(newGraph, positions, r * params.initialTempFactor * 0.5, 0)

  def step(state: LayoutState3D, params: Params = defaultParams): LayoutState3D =
    val nodes = state.graph.nodes
    val n     = nodes.size
    if state.done then state
    else if n == 0 then state.copy(temperature = 0)
    else
      val k    = params.k
      val idx  = nodes.iterator.zipWithIndex.toMap
      val pos  = nodes.iterator.map(state.positions).toArray
      val disp = Array.fill(n)(Vec3.zero)

      // Repulsion, all pairs: |f| = k²/d away from each other.
      var i = 0
      while i < n do
        var j = i + 1
        while j < n do
          val delta = pos(i) - pos(j)
          val d     = math.max(delta.length, 1e-4)
          val push  = delta * ((k * k) / (d * d))
          disp(i) = disp(i) + push
          disp(j) = disp(j) - push
          j += 1
        i += 1

      // Attraction along edges: |f| = d²/k toward each other. Self-loops exert
      // no spring force (their delta is ~0 and its direction is meaningless).
      for (s, t) <- state.graph.edges if s != t do
        val si    = idx(s)
        val ti    = idx(t)
        val delta = pos(si) - pos(ti)
        val d     = math.max(delta.length, 1e-4)
        val pull  = delta * (d / k)
        disp(si) = disp(si) - pull
        disp(ti) = disp(ti) + pull

      // Gravity toward the origin, then displace — capped by temperature.
      val t = state.temperature
      val newPositions =
        nodes.iterator.zipWithIndex
          .map: (id, i) =>
            val d    = disp(i) - pos(i) * params.gravity
            val len  = d.length
            val move = if len <= t then d else d * (t / len)
            id -> (pos(i) + move)
          .toMap

      val floor  = k * params.minTempFactor
      val cooled = t * params.cooling
      state.copy(
        positions = newPositions,
        temperature = if cooled < floor then 0 else cooled,
        iteration = state.iteration + 1
      )

  /** Run to convergence (tests, one-shot layouts). `maxSteps` is a backstop,
    * not a tuning knob — cooling guarantees termination well before it.
    */
  def run(graph: LayoutGraph, params: Params = defaultParams, maxSteps: Int = 2000): LayoutState3D =
    var state = initial(graph, params)
    var steps = 0
    while !state.done && steps < maxSteps do
      state = step(state, params)
      steps += 1
    state
