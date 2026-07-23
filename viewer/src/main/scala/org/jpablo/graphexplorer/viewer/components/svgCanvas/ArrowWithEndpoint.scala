package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.svgCanvas.arrowHeadMarker
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
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

  val updatedPathData = SVGPathParser.parse(pathData)
    .map { commands =>
      if action.endpoint.isSource then PathCommand.moveOrigin(commands, point.toTuple)
      else PathCommand.moveTarget(commands, point.toTuple)
    }
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
