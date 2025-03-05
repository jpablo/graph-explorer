package org.jpablo.graphexplorer.viewer.components.attributes.previews

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.NodeStyle
import org.scalajs.dom.SVGSVGElement

def NodeStylePreview(style: NodeStyle, width: Int = 100, height: Int = 20): Option[() => ReactiveSvgElement[SVGSVGElement]] =
  style match
    case NodeStyle.solid =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.rect(
            svg.x := "2",
            svg.y := "2",
            svg.width := (width - 4).toString,
            svg.height := (height - 4).toString,
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case NodeStyle.dashed =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.rect(
            svg.x := "2",
            svg.y := "2",
            svg.width := (width - 4).toString,
            svg.height := (height - 4).toString,
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2",
            svg.strokeDashArray := "5,2"
          )
        )
      )

    case NodeStyle.dotted =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.rect(
            svg.x := "2",
            svg.y := "2",
            svg.width := (width - 4).toString,
            svg.height := (height - 4).toString,
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2",
            svg.strokeDashArray := "1,5"
          )
        )
      )

    case NodeStyle.bold =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.rect(
            svg.x := "2",
            svg.y := "2",
            svg.width := (width - 4).toString,
            svg.height := (height - 4).toString,
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "4"
          )
        )
      )

    case NodeStyle.rounded =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.rect(
            svg.x := "2",
            svg.y := "2",
            svg.width := (width - 4).toString,
            svg.height := (height - 4).toString,
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2",
            svg.rx := "5",
            svg.ry := "5"
          )
        )
      )

    case NodeStyle.diagonals =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          // Main rectangle
          svg.rect(
            svg.x := "2",
            svg.y := "2",
            svg.width := (width - 4).toString,
            svg.height := (height - 4).toString,
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          ),
          // Left diagonals
          svg.polyline(
            svg.points := s"2,8 8,2",
            svg.fill := "none",
            svg.stroke := "currentColor",
            svg.strokeWidth := "2"
          ),
          svg.polyline(
            svg.points := s"2,${height - 8} 8,${height - 2}",
            svg.fill := "none",
            svg.stroke := "currentColor",
            svg.strokeWidth := "2"
          ),
          // Right diagonals
          svg.polyline(
            svg.points := s"${width - 8},2 ${width - 2},8",
            svg.fill := "none",
            svg.stroke := "currentColor",
            svg.strokeWidth := "2"
          ),
          svg.polyline(
            svg.points := s"${width - 8},${height - 2} ${width - 2},${height - 8}",
            svg.fill := "none",
            svg.stroke := "currentColor",
            svg.strokeWidth := "2"
          )
        )
      )

    case NodeStyle.invis => None
    
    case _ => None // Handle any remaining styles (striped, wedged) with no preview for now
