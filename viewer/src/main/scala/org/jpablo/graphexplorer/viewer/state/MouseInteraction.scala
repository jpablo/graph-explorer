package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.components.SelectionRect
import org.jpablo.graphexplorer.viewer.components.Point2d
import com.raquo.laminar.api.L.*
import com.raquo.airstream.ownership.OneTimeOwner
import org.jpablo.graphexplorer.viewer.components.Action

object MouseInteraction:

  given owner: Owner = OneTimeOwner(() => ())

  enum CanvasMouseEvent:
    case MouseDown(pos: Point2d[Double], shift: Boolean, action: Action)
    case MouseMove(pos: Point2d[Double], shift: java.lang.Boolean)
    case MouseUp(pos: Point2d[Double], shift: Boolean, action: Action)

  import CanvasMouseEvent.*

  val mouseEvent = EventBus[CanvasMouseEvent]()

  def emitEvent(event: CanvasMouseEvent): Unit =
    mouseEvent.emit(event)

  val selectionRect: Var[Option[SelectionRect]] = Var(None)

  mouseEvent.events.foreach:
    case MouseDown(pos, shift, action) =>
      selectionRect.set(Some(SelectionRect(pos.x, pos.y, pos.x, pos.y, shift, action)))

    case MouseMove(pos, shift) =>
      selectionRect.update(_.map(_.copy(endX = pos.x, endY = pos.y, shift = shift)))

    case MouseUp(pos, shift, action) =>
      selectionRect.set(None)
