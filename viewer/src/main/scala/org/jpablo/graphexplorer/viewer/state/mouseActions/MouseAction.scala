package org.jpablo.graphexplorer.viewer.state.mouseActions

import com.raquo.airstream.state.SourceVar
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.Inactive
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, MouseActionRect}

import scala.util.Success

enum ArrowEndpoint derives CanEqual:
  case source, target

  def isSource = this == source

enum MouseAction derives CanEqual:
  case Inactive
  case ExtendSelectionAction(rect: MouseActionRect)
  case AddNewArrowAction(rect: MouseActionRect, originator: SelectableElement)
  case MoveArrowEndpointAction(rect: MouseActionRect, originator: SelectableElement, endpoint: ArrowEndpoint)

import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.*

class MouseActionVar(initial: MouseAction = Inactive):

  val sourceVar = SourceVar[MouseAction](initial = Success(initial))
  export sourceVar.{now, signal}

//  sourceVar.signal.foreach(e => pprint.log(e))(unsafeWindowOwner)

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
