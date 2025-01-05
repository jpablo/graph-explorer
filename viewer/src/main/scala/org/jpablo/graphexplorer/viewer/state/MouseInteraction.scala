package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.components.SelectionRect
import org.jpablo.graphexplorer.viewer.components.Point2d
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.OneTimeOwner

object MouseInteraction:

  given owner: Owner = OneTimeOwner(() => ())

  enum CanvasMouseEvent:
    case MouseDown(pos: Point2d[Double], shift: Boolean)
    case MouseMove(pos: Point2d[Double], shift: Boolean) 
    case MouseUp(pos: Point2d[Double], shift: Boolean)

  import CanvasMouseEvent.*

  val mouseEvent = EventBus[CanvasMouseEvent]()

  def emitEvent(event: CanvasMouseEvent): Unit =
    mouseEvent.emit(event)

  val selectionRect: Var[Option[SelectionRect]] = Var(None)

  mouseEvent.events.foreach:
    case MouseDown(pos, shift) =>
      selectionRect.set(Some(SelectionRect(pos.x, pos.y, pos.x, pos.y, shift)))
    
    case MouseMove(pos, shift) =>
      selectionRect.update(_.map(_.copy(endX = pos.x, endY = pos.y, shift = shift)))

    case MouseUp(pos, shift) =>
      selectionRect.set(None)
