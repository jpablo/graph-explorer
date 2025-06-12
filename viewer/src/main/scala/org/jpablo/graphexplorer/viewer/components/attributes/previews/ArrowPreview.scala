package org.jpablo.graphexplorer.viewer.components.attributes.previews

import com.raquo.laminar.api.L.svg
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.ArrowType

def ArrowPreview(
    arrowType: ArrowType,
    width:     Int = 100,
    height:    Int = 20,
    angle:     Double = 0
): Option[() => ReactiveSvgElement[dom.svg.SVG]] = {

  def rotationTransform(viewBox: String, angle: Double): String =
    val Array(x, y, w, h) = viewBox.split(" ").map(_.toDouble)
    val centerX           = x + w / 2
    val centerY           = y + h / 2
    s"rotate($angle $centerX $centerY)"

  def normal(fill: Boolean, angle: Double = 0) =
    val viewBox = "10 0 41 20"
    svg.svg(
      svg.width   := width.toString,
      svg.height  := height.toString,
      svg.viewBox := viewBox,
      svg.g(
        svg.transform := rotationTransform(viewBox, angle),
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
          svg.style  := s"fill: ${if fill then "currentColor" else "none"}"
        )
      )
    )

  arrowType match
    case ArrowType.normal =>
      Some(() => normal(fill = true, angle = angle))

    case ArrowType.inv =>
      Some(() =>
        val viewBox = "0 0 55 20"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
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
        val viewBox = "0 0 50 20"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
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
        val viewBox = "0 0 55 20"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
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
        val viewBox = "0 0 50 20"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
            svg.line(
              svg.cls := "arrow-preview",
              svg.x1  := "0",
              svg.y1  := "10",
              svg.x2  := "50",
              svg.y2  := "10"
            )
          )
        )
      )

    case ArrowType.tee =>
      Some(() =>
        val viewBox = "0 0 50 20"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
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
      Some(() => normal(fill = false, angle = angle))

    case ArrowType.diamond =>
      Some(() =>
        val viewBox = "0 0 55 20"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
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
        val viewBox = "0 0 55 20"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
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
        val viewBox = "0 0 55 15"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
            svg.path(
              svg.cls := "arrow-preview",
              svg.d   := "M0 7.10C12.35 7.10 26.11 7.10 39.29 7.10"
            ),
            svg.polygon(
              svg.cls    := "arrow-preview",
              svg.points := "39.23 7.10 55.00 0.00 47.65 7.10 54.48 7.10 54.48 7.10 54.48 7.10 47.65 7.10 55.00 14.19 39.23 7.10"
            )
          )
        )
      )

    case ArrowType.box =>
      Some(() =>
        val viewBox = "0 0 50 20"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
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
        val viewBox = "0 0 55 20"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
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
              svg.style  := s"fill: none"
            )
          )
        )
      )

    case ArrowType.halfvee =>
      Some(() =>
        val viewBox = "0 0 55 20"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
            svg.path(
              svg.cls := "arrow-preview",
              svg.d   := "M0 7.07C12.44 7.07 26.29 7.07 39.59 7.07"
            ),
            svg.polygon(
              svg.cls    := "arrow-preview",
              svg.points := "55 7.07 39.30 0 47.49 7.07 39.30 7.07 39.30 7.07 55 7.07"
            )
          )
        )
      )

    case ArrowType.vee =>
      Some(() =>
        val viewBox = "65 -23 25 10"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
            svg.path(
              svg.cls := "arrow-preview",
              svg.d   := "M54.4,-18C61.97,-18 70.38,-18 88,-18"
            ),
            svg.polygon(
              svg.cls    := "arrow-preview",
              svg.points := "88.4,-18 78.4,-22.5 84.62,-18 78.4,-18 78.4,-18 78.4,-18 84.62,-18 78.4,-13.5 88.4,-18"
            )
          )
        )
      )

    case ArrowType.curve =>
      Some(() =>
        val viewBox = "65 -23 25 10"
        svg.svg(
          svg.width   := width.toString,
          svg.height  := height.toString,
          svg.viewBox := viewBox,
          svg.g(
            svg.transform := rotationTransform(viewBox, angle),
            svg.path(
              svg.cls := "arrow-preview",
              svg.d   := "M54.4,-18C62.23,-18 70.96,-18 79.32,-18"
            ),
            svg.polyline(
              svg.cls    := "arrow-preview",
              svg.points := "89.62,-18 79.12,-18"
            ),
            svg.path(
              svg.cls   := "arrow-preview",
              svg.style := s"fill: none",
              svg.d     := "M84.12,-13C90.78,-13 90.78,-23 84.12,-23"
            )
          )
        )
      )
}
