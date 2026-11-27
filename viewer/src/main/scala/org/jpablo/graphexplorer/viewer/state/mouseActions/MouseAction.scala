package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.airstream.state.SourceVar
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.models.{ArrowDirection, ArrowId, NodeId}
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, MouseActionRect}

import scala.util.Success

enum ArrowEndpoint derives CanEqual:
  case source, target

  def isSource = this == source
  def isTarget = this == target

enum MouseAction derives CanEqual:
  case Inactive
  case ExtendSelectionAction(rect: MouseActionRect)
  case AddNewArrowAction(
      rect:       MouseActionRect,
      originator: SelectableElement,
      direction:  ArrowDirection,
      /** The record CELL selected when the drag started — the new arrow's port
        * on the originator side (minted into the label on drop if needed). */
      sourceCellPath: Option[List[Int]] = None
  )
  case MoveArrowEndpointAction(rect: MouseActionRect, originator: SelectableElement, endpoint: ArrowEndpoint)

  /** A press on a node (manual layout, ADR 0004). Until the pointer moves past [[NodePress.ThresholdPx]], the press
    * is a click. Then the press becomes a drag of the nodes, or the canvas refuses it.
    *
    * @param alt
    *   true while the Alt key (⌥) is down: the drag does not snap to the grid.
    * @param clickOnRelease
    *   true when the release of a click must still select: a press on a selected node keeps a multi-selection, so
    *   that a drag can move all of it.
    */
  case MoveNodesAction(rect: MouseActionRect, pressed: NodeId, alt: Boolean, clickOnRelease: Boolean, phase: NodePress)

  /** A press on a bend handle or on the path of the selected arrow of a free layout (ADR 0004, part 4). As for a node,
    * a short move is a click. A longer move drags the bend.
    *
    * @param index
    *   the index of the pressed bend. A press on the path has no bend yet: the drag finds the index of the new bend.
    * @param insert
    *   true for a press on the path: the drag adds a bend at the place of the press.
    */
  case MoveBendAction(rect: MouseActionRect, arrow: ArrowId, index: Int, insert: Boolean, phase: NodePress)

  def name: String =
    this match
      case Inactive => "Inactive"
      case other    => other.getClass.getSimpleName

/** The phase of a press on a node. */
enum NodePress derives CanEqual:
  /** The pointer has not moved past the threshold yet: the press is a click. */
  case Pending

  /** The nodes follow the pointer. */
  case Dragging

  /** The canvas refused the drag (a locked diagram, an automatic layout). The rest of the gesture does nothing. */
  case Inert

object NodePress:
  /** The movement in client pixels that makes a press a drag. A shorter movement is a click. */
  val ThresholdPx = 4.0

  /** True when the pointer moved past the threshold from the start of `rect`. */
  def isDrag(rect: MouseActionRect): Boolean =
    math.hypot(rect.end.x - rect.start.x, rect.end.y - rect.start.y) >= ThresholdPx

import MouseAction.*

class MouseActionVar(initial: MouseAction = Inactive):

  val sourceVar = SourceVar[MouseAction](initial = Success(initial))

  export sourceVar.now

  val signal = sourceVar.signal.distinct

  def start(mouseAction: MouseAction): Unit =
    sourceVar.set(mouseAction)

  def inactive() =
    sourceVar.set(Inactive)

  def updateEndpoint(end: ClientPoint, shift: Boolean): Unit =
    sourceVar.update:
      case Inactive                   => Inactive
      case a: ExtendSelectionAction   => a.modify(_.rect).using(_.update(end, shift))
      case a: AddNewArrowAction       => a.modify(_.rect).using(_.update(end, shift))
      case a: MoveArrowEndpointAction => a.modify(_.rect).using(_.update(end, shift))
      case a: MoveNodesAction         => a.modify(_.rect).using(_.update(end, a.rect.shift))
      case a: MoveBendAction          => a.modify(_.rect).using(_.update(end, a.rect.shift))
