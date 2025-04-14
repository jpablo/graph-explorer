package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.formats.svg.Command.MoveTo
import org.jpablo.graphexplorer.viewer.formats.svg.{Command, SVGPathParser}
import org.jpablo.graphexplorer.viewer.state.MouseAction

def ArrowWithEndpoint(
    rect:      Signal[Option[MouseAction.MoveArrowStartAction]],
    rootGroup: dom.svg.G
): Signal[Option[ReactiveSvgElement[dom.svg.Path]]] =
  rect.map:
    _.flatMap: action =>
      if action.rect.isEmpty then
        None
      else
        val clonedPath = action.start.get.querySelector("path").cloneNode().asInstanceOf[dom.svg.Path]
        val pathData   = clonedPath.getAttribute("d")
        val point      = action.rect.end.toSvgPoint(rootGroup.getScreenCTM())

        def updateOrigin(commands: List[Command]) =
          commands match
            case MoveTo(a, _ :: pt) :: ct => MoveTo(a, point.toTuple :: pt) :: ct
            case other                    => other

        val updatedPathData = SVGPathParser.parse(pathData).map(updateOrigin).map(Command.toData).getOrElse(pathData)
        clonedPath.setAttribute("d", updatedPathData)
        Some(foreignSvgElement(svg.path, clonedPath))
