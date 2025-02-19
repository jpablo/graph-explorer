package org.jpablo.graphexplorer.viewer.components.attributes

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
          svg.rect(
            svg.x           := "2",
            svg.y           := "2",
            svg.width       := (width - 4).toString,
            svg.height      := (height - 4).toString,
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case BorderStyle.dashed =>
      Some(() =>
        svg.svg(
          svg.width  := width.toString,
          svg.height := height.toString,
          svg.rect(
            svg.x               := "2",
            svg.y               := "2",
            svg.width           := (width - 4).toString,
            svg.height          := (height - 4).toString,
            svg.stroke          := "currentColor",
            svg.fill            := "none",
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
          svg.rect(
            svg.x               := "2",
            svg.y               := "2",
            svg.width           := (width - 4).toString,
            svg.height          := (height - 4).toString,
            svg.stroke          := "currentColor",
            svg.fill            := "none",
            svg.strokeWidth     := "2",
            svg.strokeDashArray := "1,5"
          )
        )
      )

    case BorderStyle.invis => None
