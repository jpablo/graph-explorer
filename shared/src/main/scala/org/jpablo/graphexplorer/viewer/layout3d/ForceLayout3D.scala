package org.jpablo.graphexplorer.viewer.layout3d

/** Fruchterman–Reingold force layout in three dimensions.
  *
  * Deliberately deterministic: initial placement is a Fibonacci sphere (evenly
  * spread, no RNG), so the same graph always produces the same layout and tests
  * can assert on geometry. All-pairs repulsion is O(n²) per step — fine for the
  * hundreds of nodes the viewer draws; revisit (Barnes–Hut) before pointing it
  * at thousands.
  */
object ForceLayout3D extends Layout3D:

  val id    = "force"
  val label = "Force"

  override val knobs: List[Knob3D] = List(
    Knob3D("k", "Edge length", 0.4, 3.0, 0.05, 1.0),
    Knob3D("gravity", "Gravity", 0.0, 0.3, 0.005, 0.05),
    Knob3D("cohesion", "Cohesion", 0.0, 0.6, 0.01, 0.15)
  )

  def paramsFrom(values: Map[String, Double]): Params =
    Params(
      k = values.getOrElse("k", defaultParams.k),
      gravity = values.getOrElse("gravity", defaultParams.gravity),
      cohesion = values.getOrElse("cohesion", defaultParams.cohesion)
    )

  override def withKnobs(values: Map[String, Double]): Layout3D =
    Configured(paramsFrom(values))

  def reheat(state: LayoutState3D): LayoutState3D = reheat(state, defaultParams)

  def reheat(state: LayoutState3D, params: Params): LayoutState3D =
    state.copy(temperature = math.max(state.temperature, params.k * 0.35))

  /** ForceLayout3D under specific parameters; same id, so a knob change is
    * not an algorithm switch.
    */
  private final class Configured(params: Params) extends Layout3D:
    def id    = ForceLayout3D.id
    def label = ForceLayout3D.label
    override def knobs = ForceLayout3D.knobs
    override def withKnobs(values: Map[String, Double]) = ForceLayout3D.withKnobs(values)
    def initial(graph: LayoutGraph)                     = ForceLayout3D.initial(graph, params)
    def sync(state: LayoutState3D, newGraph: LayoutGraph) = ForceLayout3D.sync(state, newGraph, params)
    def step(state: LayoutState3D)                      = ForceLayout3D.step(state, params)
    def reheat(state: LayoutState3D)                    = ForceLayout3D.reheat(state, params)

  case class Params(
      /** Ideal edge length, in world units. Everything else scales off it. */
      k: Double = 1.0,
      /** Uniform pull toward the origin; keeps disconnected components from
        * drifting apart forever (repulsion alone is unbounded).
        */
      gravity: Double = 0.05,
      /** Pull of each cluster member toward its cluster's barycenter — what
        * keeps a group reading as a group in space.
        */
      cohesion: Double = 0.15,
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

  def initial(graph: LayoutGraph): LayoutState3D = initial(graph, defaultParams)

  def initial(graph: LayoutGraph, params: Params): LayoutState3D =
    val n = graph.nodes.size
    val r = radiusFor(n, params.k)
    val positions =
      graph.nodes.iterator.zipWithIndex
        .map((nodeId, i) => nodeId -> fibonacciSphere(i, n) * r)
        .toMap
    LayoutState3D(id, graph, positions, r * params.initialTempFactor, 0)

  /** Adopt a new graph (or a foreign algorithm's state) while keeping the
    * layout the user is looking at: surviving nodes keep their positions
    * exactly; a new node starts at the barycenter of its already-placed
    * neighbors (nudged off it so the spring force is well-defined), or on the
    * placement sphere when it has none. Reheats to half the initial
    * temperature so the change animates in instead of teleporting.
    *
    * Identity when the topology is unchanged and the state is already this
    * algorithm's — attribute edits must not wiggle a settled layout.
    */
  def sync(state: LayoutState3D, newGraph: LayoutGraph): LayoutState3D =
    sync(state, newGraph, defaultParams)

  def sync(state: LayoutState3D, newGraph: LayoutGraph, params: Params): LayoutState3D =
    if state.graph == newGraph && state.algoId == id then state
    else
      val n       = newGraph.nodes.size
      val r       = radiusFor(n, params.k)
      val nodeSet = newGraph.nodes.toSet
      val kept    = state.positions.filter((nodeId, _) => nodeSet.contains(nodeId))
      val positions = newGraph.nodes.iterator.zipWithIndex.foldLeft(kept):
        case (acc, (nodeId, i)) =>
          if acc.contains(nodeId) then acc
          else
            val placedNeighbors =
              newGraph.edges.iterator
                .collect:
                  case (s, t) if s == nodeId && acc.contains(t) => acc(t)
                  case (s, t) if t == nodeId && acc.contains(s) => acc(s)
                .toVector
            val pos =
              if placedNeighbors.isEmpty then fibonacciSphere(i, n) * r
              else
                val barycenter = placedNeighbors.reduce(_ + _) * (1.0 / placedNeighbors.size)
                barycenter + fibonacciSphere(i, n.max(2)) * (params.k * 0.5)
            acc.updated(nodeId, pos)
      LayoutState3D(id, newGraph, positions, r * params.initialTempFactor * 0.5, 0)

  def step(state: LayoutState3D): LayoutState3D = step(state, defaultParams)

  def step(state: LayoutState3D, params: Params): LayoutState3D =
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

      // Cohesion: members lean toward their cluster's barycenter, linearly in
      // distance (like gravity, but per group).
      if params.cohesion > 0 then
        for members <- state.graph.clusters if members.size >= 2 do
          val idxs = members.map(idx)
          var bx = 0.0; var by = 0.0; var bz = 0.0
          for i <- idxs do
            bx += pos(i).x; by += pos(i).y; bz += pos(i).z
          val bary = Vec3(bx / idxs.size, by / idxs.size, bz / idxs.size)
          for i <- idxs do disp(i) = disp(i) - (pos(i) - bary) * params.cohesion

      // Gravity toward the origin, then displace — capped by temperature.
      // Pinned nodes (a drag in progress) stay exactly put; they still exert
      // forces on everyone else, which is what makes tugging one node feel
      // like tugging the graph.
      val t = state.temperature
      val newPositions =
        nodes.iterator.zipWithIndex
          .map: (nodeId, i) =>
            if state.pinned.contains(nodeId) then nodeId -> pos(i)
            else
              val d    = disp(i) - pos(i) * params.gravity
              val len  = d.length
              val move = if len <= t then d else d * (t / len)
              nodeId -> (pos(i) + move)
          .toMap

      val floor  = k * params.minTempFactor
      val cooled = t * params.cooling
      state.copy(
        positions = newPositions,
        temperature = if cooled < floor then 0 else cooled,
        iteration = state.iteration + 1
      )

  /** Run to convergence with specific params (tests). Prefer [[Layout3D.run]]
    * for the default-params case.
    */
  def run(graph: LayoutGraph, params: Params = defaultParams, maxSteps: Int = 2000): LayoutState3D =
    var state = initial(graph, params)
    var steps = 0
    while !state.done && steps < maxSteps do
      state = step(state, params)
      steps += 1
    state
