package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.L.svg
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.EdgeStyle
import org.scalajs.dom.SVGSVGElement
import com.raquo.laminar.nodes.ReactiveSvgElement

def EdgeStylePreview(
    style:  EdgeStyle,
    width:  Int = 100,
    height: Int = 20
): Option[() => ReactiveSvgElement[SVGSVGElement]] =
  style match
    case EdgeStyle.solid =>
      Some(() =>
        svg.svg(
          svg.width  := width.toString,
          svg.height := height.toString,
          svg.line(
            svg.x1          := "0",
            svg.y1          := (height / 2).toString,
            svg.x2          := width.toString,
            svg.y2          := (height / 2).toString,
            svg.stroke      := "currentColor",
            svg.strokeWidth := "2"
          )
        )
      )

    case EdgeStyle.dashed =>
      Some(() =>
        svg.svg(
          svg.width  := width.toString,
          svg.height := height.toString,
          svg.line(
            svg.x1              := "0",
            svg.y1              := (height / 2).toString,
            svg.x2              := width.toString,
            svg.y2              := (height / 2).toString,
            svg.stroke          := "currentColor",
            svg.strokeWidth     := "2",
            svg.strokeDashArray := "5,2"
          )
        )
      )

    case EdgeStyle.dotted =>
      Some(() =>
        svg.svg(
          svg.width  := width.toString,
          svg.height := height.toString,
          svg.line(
            svg.x1              := "0",
            svg.y1              := (height / 2).toString,
            svg.x2              := width.toString,
            svg.y2              := (height / 2).toString,
            svg.stroke          := "currentColor",
            svg.strokeWidth     := "2",
            svg.strokeDashArray := "1,5"
          )
        )
      )

    case EdgeStyle.bold =>
      Some(() =>
        svg.svg(
          svg.width  := width.toString,
          svg.height := height.toString,
          svg.line(
            svg.x1          := "0",
            svg.y1          := (height / 2).toString,
            svg.x2          := width.toString,
            svg.y2          := (height / 2).toString,
            svg.stroke      := "currentColor",
            svg.strokeWidth := "4"
          )
        )
      )

    case EdgeStyle.invis   => None
    case EdgeStyle.tapered => None
