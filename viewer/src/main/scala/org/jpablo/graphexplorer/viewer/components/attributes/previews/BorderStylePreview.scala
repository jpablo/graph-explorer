package org.jpablo.graphexplorer.viewer.components.attributes.previews

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.BorderStyle
import org.scalajs.dom.SVGSVGElement

def BorderStylePreview(
    style:  BorderStyle,
    width:  Int = 100,
    height: Int = 20
): Option[() => ReactiveSvgElement[SVGSVGElement]] =
  style match
    case BorderStyle.solid =>
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

    case BorderStyle.dashed =>
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

    case BorderStyle.dotted =>
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
