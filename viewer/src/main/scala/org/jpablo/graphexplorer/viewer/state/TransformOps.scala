package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.utils.SvgPoint

trait TransformOps:
  this: ViewerState =>

  val translateXY = Var(SvgPoint.origin)

  val zoomValue = Var(1.0)

  val fitDiagram = EventBus[Unit]()

  val transform =
    zoomValue.signal
      .combineWith(translateXY.signal)
      .map: (z, p) =>
        s"scale($z) translate(${p.x} ${p.y})"

  def resetView(): Unit =
    Var.set(
      zoomValue -> 0.90,
      translateXY -> SvgPoint.origin
    )
  
        
