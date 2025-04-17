package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.formats.svg.PathCommand.MoveTo
import org.jpablo.graphexplorer.viewer.formats.svg.{PathCommand, SVGPathParser}
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction

def ArrowWithEndpoint(
    action:    MouseAction.MoveArrowEndpointAction,
    rootGroup: dom.svg.G
): Option[ReactiveSvgElement[dom.svg.Path]] =
  if action.rect.isEmpty then
    None
  else
    val clonedPath = action.originator.ref.querySelector("path").cloneNode().asInstanceOf[dom.svg.Path]
    val pathData   = clonedPath.getAttribute("d")
    val point      = action.rect.end.toSvgPoint(rootGroup.getScreenCTM())

    def updateOrigin(commands: List[PathCommand]) =
      commands match
        case MoveTo(a, _ :: pt) :: ct => MoveTo(a, point.toTuple :: pt) :: ct
        case other                    => other

    val updatedPathData = SVGPathParser.parse(pathData).map(updateOrigin).map(PathCommand.toData).getOrElse(pathData)
    clonedPath.setAttribute("d", updatedPathData)
    Some(foreignSvgElement(svg.path, clonedPath))
