package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.arrowHeadMarker
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.formats.svg.PathCommand.*
import org.jpablo.graphexplorer.viewer.formats.svg.{PathCommand, SVGPathParser}
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.MoveArrowEndpointAction

def ArrowBetweenPointerAndEndpoint(
    action:    MoveArrowEndpointAction,
    rootGroup: dom.svg.G
): Option[ReactiveSvgElement[dom.svg.G]] =
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

    def updateTarget(commands: List[PathCommand]) =
      commands match
        case commands =>
          // Find the last command to update the target point
          val lastIndex = commands.size - 1
          commands.zipWithIndex.map:
            case (LineTo(a, pts), i) if i == lastIndex     => LineTo(a, pts.init :+ point.toTuple)
            case (CurveTo(a, points), i) if i == lastIndex =>
              // For CurveTo, we need to update the last point in the last triplet
              val updatedPoints = points.init :+ (points.last._1, points.last._2, point.toTuple)
              CurveTo(a, updatedPoints)
            case (cmd, _) => cmd

    val updatedPathData = SVGPathParser.parse(pathData)
      .map(if action.endpoint.isSource then updateOrigin else updateTarget)
      .map(PathCommand.toData)
      .getOrElse(pathData)

    clonedPath.setAttribute("id", "dragging-arrow-line")
    clonedPath.setAttribute("d", updatedPathData)
    if action.endpoint.isTarget then
      clonedPath.setAttribute("marker-end", "url(#arrowhead)")

    Some(
      svg.g(
        svg.idAttr := "dragging-arrow-group",
        svg.defs(arrowHeadMarker),
        foreignSvgElement(svg.path, clonedPath)
      )
    )
