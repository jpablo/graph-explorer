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
): Signal[Option[ReactiveSvgElement[dom.svg.Path]]] =
  rect.map:
    _.flatMap: action =>
      if action.rect.isEmpty then
        None
      else
        val svgArrowGroup = action.start.get
        val clonedPath = svgArrowGroup.querySelector("path").cloneNode().asInstanceOf[dom.svg.Path]

        val pathData = clonedPath.getAttribute("d")
        val startPoint = SVGPathParser.parseCoordinatesAfterM(pathData)
        val point = action.rect.end.toSvgPoint(rootGroup.getScreenCTM())

        val startBBox = svgArrowGroup.getBBox()
        // Calculate center point
        val centerX = startBBox.x + startBBox.width / 2
        val centerY = startBBox.y + startBBox.height / 2

        val (x1, y1) = (centerX, centerY)
        val scale = SvgUtils.calculateSimpleScale(rootGroup, 1, clientSize = 2)
        Some(
          foreignSvgElement(svg.path, clonedPath)
        )
