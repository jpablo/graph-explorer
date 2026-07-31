package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.airstream.state.SourceVar
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.models.ArrowDirection
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

  def name: String =
    this match
      case Inactive => "Inactive"
      case other    => other.getClass.getSimpleName

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
