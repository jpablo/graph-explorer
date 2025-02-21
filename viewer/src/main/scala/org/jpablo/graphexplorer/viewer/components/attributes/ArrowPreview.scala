package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.svg
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.ArrowType
import org.scalajs.dom.SVGSVGElement
import com.raquo.laminar.nodes.ReactiveSvgElement

def ArrowPreview(
    arrowType: ArrowType,
    width:     Int = 100,
    height:    Int = 20
): Option[() => ReactiveSvgElement[SVGSVGElement]] =
  arrowType match
    case ArrowType.normal =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points := "40,5 50,10 40,15",
              svg.stroke := "currentColor",
              svg.fill   := "currentColor"
            )
          )
        )
      )

    case ArrowType.inv =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points := "50,5 40,10 50,15",
              svg.stroke := "currentColor",
              svg.fill   := "currentColor"
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
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.circle(
              svg.cx     := "45",
              svg.cy     := "10",
              svg.r      := "4",
              svg.stroke := "currentColor",
              svg.fill   := "currentColor"
            )
          )
        )
      )

    case ArrowType.invdot =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points := "50,5 40,10 50,15",
              svg.stroke := "currentColor",
              svg.fill   := "currentColor"
            ),
            svg.circle(
              svg.cx     := "35",
              svg.cy     := "10",
              svg.r      := "4",
              svg.stroke := "currentColor",
              svg.fill   := "currentColor"
            )
          )
        )
      )

    case ArrowType.odot =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.circle(
              svg.cx     := "45",
              svg.cy     := "10",
              svg.r      := "4",
              svg.stroke := "currentColor",
              svg.fill   := "none"
            )
          )
        )
      )

    case ArrowType.invodot =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "30",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.circle(
              svg.cx     := "35",
              svg.cy     := "10",
              svg.r      := "4",
              svg.stroke := "currentColor",
              svg.fill   := "none"
            ),
            svg.polygon(
              svg.points := "45,5 40,10 45,15",
              svg.stroke := "currentColor",
              svg.fill   := "currentColor"
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
            svg.x1          := "0",
            svg.y1          := "10",
            svg.x2          := "50",
            svg.y2          := "10",
            svg.stroke      := "currentColor",
            svg.strokeWidth := "2"
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
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.line(
              svg.x1          := "40",
              svg.y1          := "5",
              svg.x2          := "40",
              svg.y2          := "15",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case ArrowType.empty =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points := "40,5 50,10 40,15",
              svg.stroke := "currentColor",
              svg.fill   := "none"
            )
          )
        )
      )

    case ArrowType.invempty =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points := "50,5 40,10 50,15",
              svg.stroke := "currentColor",
              svg.fill   := "none"
            )
          )
        )
      )

    case ArrowType.diamond =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "35",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points := "35,10 42,5 49,10 42,15",
              svg.stroke := "currentColor",
              svg.fill   := "currentColor"
            )
          )
        )
      )

    case ArrowType.odiamond =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "35",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points := "35,10 42,5 49,10 42,15",
              svg.stroke := "currentColor",
              svg.fill   := "none"
            )
          )
        )
      )

    case ArrowType.ediamond =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "35",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points := "35,10 42,5 49,10 42,15",
              svg.stroke := "currentColor",
              svg.fill   := "none"
            ),
            svg.line(
              svg.x1          := "42",
              svg.y1          := "5",
              svg.x2          := "42",
              svg.y2          := "15",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
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
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points := "50,5 40,10 50,15",
              svg.stroke := "currentColor",
              svg.fill   := "currentColor"
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
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "35",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.rect(
              svg.x      := "35",
              svg.y      := "5",
              svg.width  := "10",
              svg.height := "10",
              svg.stroke := "currentColor",
              svg.fill   := "currentColor"
            )
          )
        )
      )

    case ArrowType.obox =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "35",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.rect(
              svg.x      := "35",
              svg.y      := "5",
              svg.width  := "10",
              svg.height := "10",
              svg.stroke := "currentColor",
              svg.fill   := "none"
            )
          )
        )
      )

    case ArrowType.open =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points := "40,5 50,10 40,15",
              svg.stroke := "currentColor",
              svg.fill   := "none"
            )
          )
        )
      )

    case ArrowType.halfopen =>
      Some(() =>
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "45",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
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
          svg.viewBox := "0 0 50 20",
          svg.g(
            svg.line(
              svg.x1          := "0",
              svg.y1          := "10",
              svg.x2          := "40",
              svg.y2          := "10",
              svg.stroke      := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points := "50,10 40,5 46,10 40,10 40,10 40,10 46,10 40,15 50,10",
              svg.stroke := "currentColor",
              svg.fill   := "currentColor"
            )
          )
        )
      )
