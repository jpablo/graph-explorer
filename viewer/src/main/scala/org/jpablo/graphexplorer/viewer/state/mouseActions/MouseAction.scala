package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.airstream.state.SourceVar
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.Inactive
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, UserActionRect}

import scala.util.Success

enum MouseAction derives CanEqual:
  case Inactive
  case ExtendSelectionAction(rect: UserActionRect)
  case AddNewArrowAction(rect: UserActionRect, start: SelectableElement)
  case MoveArrowSourceAction(rect: UserActionRect, start: SelectableElement)

import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.*

class MouseActionVar(initial: MouseAction = Inactive):

  val sourceVar = SourceVar[MouseAction](initial = Success(initial))
  export sourceVar.{now, signal}

  sourceVar.signal.foreach(e => pprint.log(e))(unsafeWindowOwner)

  def start(mouseAction: MouseAction): Unit =
    sourceVar.set(mouseAction)

  def inactive() =
    sourceVar.set(Inactive)

  def updateEndpoint(end: ClientPoint, shift: Boolean): Unit =
    sourceVar.update:
      case Inactive                           => Inactive
      case ExtendSelectionAction(rect)        => ExtendSelectionAction(rect.update(end, shift))
      case AddNewArrowAction(rect, start)     => AddNewArrowAction(rect.update(end, shift), start)
      case MoveArrowSourceAction(rect, start) => MoveArrowSourceAction(rect.update(end, shift), start)
