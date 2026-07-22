package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.arrowHeadMarker
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
import org.jpablo.graphexplorer.viewer.formats.svg.PathCommand.*
import org.jpablo.graphexplorer.viewer.formats.svg.{PathCommand, SVGPathParser}
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.MoveArrowEndpointAction

def ArrowBetweenPointerAndEndpoint(
    action:    MoveArrowEndpointAction,
    rootGroup: dom.svg.G
): ReactiveSvgElement[dom.svg.G] =
  val basePath =
    action.originator.ref match
      case path: dom.svg.Path => path
      case _                  => action.originator.ref.querySelector("path").asInstanceOf[dom.svg.Path]
  val clonedPath = basePath.cloneNode().asInstanceOf[dom.svg.Path]
  // The preview must not inherit the source path's inline styles: Mermaid promotes
  // linkStyle declarations to inline `!important` (and hit-area clones are inline
  // transparent), either of which would beat the #dragging-arrow-group CSS and the
  // stroke-width attribute set below, leaving the preview mis-colored or invisible.
  clonedPath.removeAttribute("style")
  val pathData = clonedPath.getAttribute("d")
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

  val arrowhead = "arrowhead"
  val arrowtail = "arrowtail"

  val scale = SvgUtils.calculateSimpleScale(rootGroup, 1, clientSize = 3)

  clonedPath.setAttribute("id", "dragging-arrow-line")
  clonedPath.setAttribute("d", updatedPathData)
  clonedPath.setAttribute("stroke-width", scale.toString)
  if action.endpoint.isTarget then
    clonedPath.setAttribute("marker-end", s"url(#$arrowhead)")
  else
    clonedPath.setAttribute("marker-start", s"url(#$arrowtail)")

  svg.g(
    svg.idAttr := "dragging-arrow-group",
    svg.defs(arrowHeadMarker(arrowhead), arrowTailMarker(arrowtail)),
    foreignSvgElement(svg.path, clonedPath)
  )
