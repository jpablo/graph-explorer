package org.jpablo.graphexplorer.viewer.components.attributes.previews

import com.raquo.laminar.api.L.svg
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.ArrowType

def ArrowPreview(
    arrowType: ArrowType,
    width:     Int = 100,
    height:    Int = 20
): Option[() => ReactiveSvgElement[dom.svg.SVG]] =
  arrowType match
    case ArrowType.normal =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 55 20",
          svg.g(
            svg.line(
              svg.cls := "arrow-preview",
              svg.x1  := "0",
              svg.y1  := "10",
              svg.x2  := "40",
              svg.y2  := "10"
            ),
            svg.polygon(
              svg.cls    := "arrow-preview",
              svg.points := "40,5 50,10 40,15",
              svg.style  := "fill: currentColor;"
            )
          )
        )
      )

    case ArrowType.inv =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 55 20",
          svg.g(
            svg.line(
              svg.cls := "arrow-preview",
              svg.x1  := "0",
              svg.y1  := "10",
              svg.x2  := "40",
              svg.y2  := "10"
            ),
            svg.polygon(
              svg.cls    := "arrow-preview",
              svg.points := "50,5 40,10 50,15",
              svg.style  := "fill: currentColor;"
            )
          )
        )
      )

    case ArrowType.dot =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.cls := "arrow-preview",
              svg.x1  := "0",
              svg.y1  := "10",
              svg.x2  := "40",
              svg.y2  := "10"
            ),
            svg.circle(
              svg.cls   := "arrow-preview",
              svg.cx    := "40",
              svg.cy    := "10",
              svg.r     := "6",
              svg.style := "fill: currentColor;"
            )
          )
        )
      )

    case ArrowType.odot =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 55 20",
          svg.g(
            svg.line(
              svg.cls := "arrow-preview",
              svg.x1  := "0",
              svg.y1  := "10",
              svg.x2  := "40",
              svg.y2  := "10"
            ),
            svg.circle(
              svg.cls         := "arrow-preview",
              svg.cx          := "45",
              svg.cy          := "10",
              svg.r           := "6",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "3",
              svg.fill        := "none"
            )
          )
        )
      )

    case ArrowType.none =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.line(
            svg.cls := "arrow-preview",
            svg.x1  := "0",
            svg.y1  := "10",
            svg.x2  := "50",
            svg.y2  := "10"
          )
        )
      )

    case ArrowType.tee =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.cls    := "arrow-preview",
              svg.x1     := "0",
              svg.y1     := "10",
              svg.x2     := "40",
              svg.y2     := "10",
              svg.stroke := "currentColor"
            ),
            svg.line(
              svg.cls    := "arrow-preview",
              svg.x1     := "40",
              svg.y1     := "5",
              svg.x2     := "40",
              svg.y2     := "15",
              svg.stroke := "currentColor"
            )
          )
        )
      )

    case ArrowType.onormal =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 52 20",
          svg.g(
            svg.line(
              svg.cls         := "arrow-preview",
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "3"
            ),
            svg.polygon(
              svg.cls    := "arrow-preview",
              svg.points := "40,5 50,10 40,15",
              svg.style  := "fill: none; stroke-width: 1px;"
            )
          )
        )
      )

    case ArrowType.diamond =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 55 20",
          svg.g(
            svg.line(
              svg.cls    := "arrow-preview",
              svg.x1     := "0",
              svg.y1     := "10",
              svg.x2     := "35",
              svg.y2     := "10",
              svg.stroke := "currentColor"
            ),
            svg.polygon(
              svg.cls    := "arrow-preview",
              svg.points := "35,10 42,5 49,10 42,15",
              svg.style  := "fill: currentColor;"
            )
          )
        )
      )

    case ArrowType.odiamond =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 55 20",
          svg.g(
            svg.line(
              svg.cls := "arrow-preview",
              svg.x1  := "0",
              svg.y1  := "10",
              svg.x2  := "35",
              svg.y2  := "10"
            ),
            svg.polygon(
              svg.cls    := "arrow-preview",
              svg.points := "35,10 42,5 49,10 42,15",
              svg.style  := "fill: none; stroke-width: 1.5px;"
            )
          )
        )
      )

    case ArrowType.crow =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.cls := "arrow-preview",
              svg.x1  := "0",
              svg.y1  := "10",
              svg.x2  := "40",
              svg.y2  := "10"
            ),
            svg.polygon(
              svg.cls    := "arrow-preview",
              svg.points := "50,5 40,10 50,15",
//              svg.stroke := "currentColor",
//              svg.fill   := "currentColor"
            )
          )
        )
      )

    case ArrowType.box =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.cls := "arrow-preview",
              svg.x1  := "0",
              svg.y1  := "10",
              svg.x2  := "35",
              svg.y2  := "10"
            ),
            svg.rect(
              svg.cls    := "arrow-preview",
              svg.x      := "35",
              svg.y      := "5",
              svg.width  := "10",
              svg.height := "10",
              svg.style  := "fill: currentColor"
            )
          )
        )
      )

    case ArrowType.obox =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 55 20",
          svg.g(
            svg.line(
              svg.cls := "arrow-preview",
              svg.x1  := "0",
              svg.y1  := "10",
              svg.x2  := "35",
              svg.y2  := "10"
            ),
            svg.rect(
              svg.cls    := "arrow-preview",
              svg.x      := "35",
              svg.y      := "5",
              svg.width  := "10",
              svg.height := "10",
              svg.style  := "stroke-width: 1.5px;"
            )
          )
        )
      )

    case ArrowType.halfvee =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 55 20",
          svg.g(
            svg.line(
              svg.cls   := "arrow-preview",
              svg.x1    := "0",
              svg.y1    := "10",
              svg.x2    := "46",
              svg.y2    := "10",
              svg.style := "stroke-width: 1.5px;"
            ),
            svg.polygon(
              svg.points := "50,10 45,5 47,10 45,10 45,10 50,10",
              svg.stroke := "currentColor",
              svg.fill   := "currentColor"
            )
          )
        )
      )

    case ArrowType.vee =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "60 -23 30 10",
          svg.g(
            svg.path(
              svg.cls := "arrow-preview",
              svg.d   := "M54.4,-18C61.97,-18 70.38,-18 88,-18"
            ),
            svg.polygon(
              svg.fill   := "black",
              svg.stroke := "black",
              svg.points := "88.4,-18 78.4,-22.5 84.62,-18 78.4,-18 78.4,-18 78.4,-18 84.62,-18 78.4,-13.5 88.4,-18"
            )
          )
        )
      )

    case ArrowType.curve =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 55 20",
          svg.g(
            svg.path(
              svg.cls := "arrow-preview",
              svg.d   := "M0,10 C15,10 25,10 48,10"
            ),
            svg.path(
              svg.cls   := "arrow-preview",
              svg.d     := "M42,5 C48,5 48,15 42,15",
              svg.style := "stroke-width: 1px;"
            )
          )
        )
      )
