package org.jpablo.graphexplorer.viewer.layout3d

import org.jpablo.graphexplorer.viewer.models.NodeId

/** The layout's view of a graph: node identity, connectivity, and cluster
  * membership — nothing else. Equality on this type is what decides whether a
  * re-emitted `visibleGraph` restarts the simulation — attribute-only edits
  * (colors, labels) produce the same LayoutGraph and must leave a settled
  * layout untouched; a membership change is a real topology change and
  * re-adopts.
  *
  * `clusters` are the node-member sets of the visible groups (each with at
  * least two members); layouts may use them for cohesion, and the renderer
  * draws hulls around them.
  */
case class LayoutGraph(
    nodes:    Vector[NodeId],
    edges:    Vector[(NodeId, NodeId)],
    clusters: Vector[Vector[NodeId]] = Vector.empty
) derives CanEqual

/** A layout snapshot, shared by every algorithm. `step` is pure
  * (state => state) so the caller decides pacing: N steps per animation frame,
  * or a loop to convergence in a test. temperature == 0 means settled; `step`
  * is then the identity.
  *
  * `algoId` names the algorithm that produced this state. It is part of the
  * sync-identity check: the same graph under a DIFFERENT algorithm must
  * re-adopt (that is what animates a layout switch), while the same graph
  * under the same algorithm must not wiggle.
  *
  * `targets` is used by direct layouts (positions computed in closed form,
  * then tweened toward); simulation layouts leave it empty.
  *
  * `pinned` nodes are held exactly where they are — a drag in progress. The
  * simulation keeps running around them (that is the point: neighbors react
  * live), so a caller pinning a node should also keep `temperature` warm.
  */
case class LayoutState3D(
    algoId:      String,
    graph:       LayoutGraph,
    positions:   Map[NodeId, Vec3],
    temperature: Double,
    iteration:   Int,
    targets:     Map[NodeId, Vec3] = Map.empty,
    pinned:      Set[NodeId] = Set.empty
) derives CanEqual:
  def done: Boolean = temperature <= 0

/** A user-tunable layout parameter: enough metadata for a generic slider.
  * Ids are algorithm-scoped; values live in the viewer session, not the state.
  */
case class Knob3D(
    id:      String,
    label:   String,
    min:     Double,
    max:     Double,
    step:    Double,
    default: Double
) derives CanEqual

/** A 3D layout algorithm. Implementations must be deterministic — the same
  * graph always produces the same layout — so tests can assert on geometry
  * and a reload never surprises.
  */
trait Layout3D:
  /** Stable identifier, stamped into states and persisted in settings. */
  def id: String

  /** Short human label for the layout selector. */
  def label: String

  /** The tunable parameters this algorithm exposes, in display order. */
  def knobs: List[Knob3D] = Nil

  /** This algorithm with the given knob values applied. The returned instance
    * keeps the same `id` — knob changes are not an algorithm switch, so sync's
    * identity semantics are unaffected; pair with [[reheat]] to make a change
    * take effect on a live state.
    */
  def withKnobs(values: Map[String, Double]): Layout3D = this

  /** Make freshly-changed parameters take effect on a live state: recompute
    * what is closed-form, warm what simulates.
    */
  def reheat(state: LayoutState3D): LayoutState3D

  /** Fresh layout for a graph, from nothing. */
  def initial(graph: LayoutGraph): LayoutState3D

  /** Adopt a (possibly foreign) state: keep the positions the user is looking
    * at, adjust to the new graph and/or this algorithm, and reheat so the
    * change animates instead of teleporting. Must be IDENTITY when the graph
    * is unchanged and the state is already this algorithm's.
    */
  def sync(state: LayoutState3D, newGraph: LayoutGraph): LayoutState3D

  /** Advance one step. Identity once `state.done`. */
  def step(state: LayoutState3D): LayoutState3D

object Layout3D:
  /** Display order for the layout selector. */
  val all: List[Layout3D] = List(ForceLayout3D, LayeredLayout3D)

  def byId(id: String): Option[Layout3D] = all.find(_.id == id)

  /** Run to convergence (tests, one-shot layouts). `maxSteps` is a backstop,
    * not a tuning knob — every algorithm's cooling guarantees termination
    * well before it.
    */
  def run(algo: Layout3D, graph: LayoutGraph, maxSteps: Int = 2000): LayoutState3D =
    var state = algo.initial(graph)
    var steps = 0
    while !state.done && steps < maxSteps do
      state = algo.step(state)
      steps += 1
    state
