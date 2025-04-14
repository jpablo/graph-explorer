package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.toSvgPoint
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
import org.jpablo.graphexplorer.viewer.state.MouseAction


def ArrowWithEndpoint(
  rect:      Signal[Option[MouseAction.MoveArrowStartAction]],
  rootGroup: dom.svg.G
): Signal[Option[ReactiveSvgElement[dom.svg.G]]] =
  rect.map:
    _.flatMap: action =>
      if action.rect.isEmpty then
        None
      else
        val svgArrowGroup = action.start.get
        val svgPath = svgArrowGroup.querySelector("path").asInstanceOf[dom.svg.Path]
        val startPoint = SVGPathParser.parseCoordinatesAfterM(svgPath.getAttribute("d"))
        pprint.log(startPoint)

        val point = action.rect.end.toSvgPoint(rootGroup.getScreenCTM())
        val startBBox = svgArrowGroup.getBBox()
        // Calculate center point
        val centerX = startBBox.x + startBBox.width / 2
        val centerY = startBBox.y + startBBox.height / 2

        val (x1, y1) = (centerX, centerY)
        val scale = SvgUtils.calculateSimpleScale(rootGroup, 1, clientSize = 2)
        Some(
          svg.g(
            svg.idAttr := "dragging-arrow-group",
            // Define the arrowhead marker
            svg.defs(
              svg.marker(
                svg.idAttr       := "arrowhead",
                svg.viewBox      := "0 0 10 10",
                svg.refX         := "9",
                svg.refY         := "5",
                svg.markerWidth  := "4", // Smaller size
                svg.markerHeight := "4", // Smaller size
                svg.orient       := "auto-start-reverse",
                svg.path(
                  // Vee style path with concave base
                  svg.d      := "M 0 0 L 10 5 L 0 10 L 2 5 z",
                  svg.fill   := "#2c70ff", // Selected border blue
                  svg.stroke := "#2c70ff"  // Match the fill color
                )
              )
            ),
            // Draw the line with the arrowhead
            svg.line(
              svg.idAttr      := "dragging-arrow-line",
              svg.x1          := x1.toString,
              svg.y1          := y1.toString,
              svg.x2          := point.x.toString,
              svg.y2          := point.y.toString,
              svg.markerEnd   := "url(#arrowhead)",
              svg.stroke      := "#2c70ff", // Selected border blue
              svg.strokeWidth := scale.toString        // Thinner line to match smaller arrowhead
            )
          )
        )
