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
  */
case class LayoutState3D(
    algoId:      String,
    graph:       LayoutGraph,
    positions:   Map[NodeId, Vec3],
    temperature: Double,
    iteration:   Int,
    targets:     Map[NodeId, Vec3] = Map.empty
) derives CanEqual:
  def done: Boolean = temperature <= 0

/** A 3D layout algorithm. Implementations must be deterministic — the same
  * graph always produces the same layout — so tests can assert on geometry
  * and a reload never surprises.
  */
trait Layout3D:
  /** Stable identifier, stamped into states and persisted in settings. */
  def id: String

  /** Short human label for the layout selector. */
  def label: String

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
