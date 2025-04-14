package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.SourceVar
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.state.MouseAction.Inactive
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, UserActionRect}

import scala.util.Success

enum MouseAction derives CanEqual:
  case Inactive
  case ExtendSelectionAction(rect: UserActionRect)
  case AddNewArrowAction(rect: UserActionRect, start: SelectableElement)
  case MoveArrowStartAction(rect: UserActionRect, start: SelectableElement)

import MouseAction.*

class MouseActionVar(initial: MouseAction = Inactive):

  val sourceVar = SourceVar[MouseAction](initial = Success(initial))
  export sourceVar.{now, signal}

  def startExtendSelection(pos: ClientPoint, shift: Boolean): Unit =
    sourceVar.set(ExtendSelectionAction(UserActionRect(pos, pos, shift)))

  def startAddNewArrow(pos: ClientPoint, shift: Boolean, start: SelectableElement): Unit =
    sourceVar.set(AddNewArrowAction(UserActionRect(pos, pos, shift), start))

  def startMoveArrowStart(pos: ClientPoint, shift: Boolean, start: SelectableElement): Unit =
    sourceVar.set(MoveArrowStartAction(UserActionRect(pos, pos, shift), start))

  def inactive() = sourceVar.set(Inactive)

  def updateEndpoint(end: ClientPoint, shift: Boolean): Unit =
    sourceVar.update:
      case Inactive                          => Inactive
      case ExtendSelectionAction(rect)       => ExtendSelectionAction(rect.update(end, shift))
      case AddNewArrowAction(rect, start)    => AddNewArrowAction(rect.update(end, shift), start)
      case MoveArrowStartAction(rect, start) => MoveArrowStartAction(rect.update(end, shift), start)
