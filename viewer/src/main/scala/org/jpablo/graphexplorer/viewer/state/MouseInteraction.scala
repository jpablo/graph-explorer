package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.components.SelectionRect
import org.jpablo.graphexplorer.viewer.components.Point2d
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.Action

object MouseInteraction:

  val selectionRect: Var[Option[SelectionRect]] = Var(None)

  def startSelection(pos: Point2d[Double], shift: Boolean, action: Action): Unit =
    selectionRect.set(Some(SelectionRect(pos.x, pos.y, pos.x, pos.y, shift, action)))

  def updateSelection(pos: Point2d[Double], shift: Boolean): Unit =
    selectionRect.update(_.map(_.copy(endX = pos.x, endY = pos.y, shift = shift)))

  def endSelection(): Unit =
    selectionRect.set(None)
