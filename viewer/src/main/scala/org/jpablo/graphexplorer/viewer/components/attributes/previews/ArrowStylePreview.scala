package org.jpablo.graphexplorer.viewer.components.attributes.previews

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.EdgeStyle

def ArrowStylePreview(
    style:  EdgeStyle,
    width:  Int = 100,
    height: Int = 20
): Option[() => ReactiveSvgElement[dom.svg.SVG]] =
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

    case EdgeStyle.tapered =>
      val taperedSVG =
        s"""<?xml version="1.0" encoding="UTF-8" standalone="no"?>
          |<!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
          |<svg width="28px" height="20px" viewBox="0 0 16 1" version="1.1" xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" xml:space="preserve" xmlns:serif="http://www.serif.com/" style="fill-rule:evenodd;clip-rule:evenodd;stroke-linejoin:round;stroke-miterlimit:2;">
          |    <g transform="matrix(1,0,0,1,-1.06429,-80.4635)">
          |        <g id="Artboard4" transform="matrix(0.912884,0,0,0.32278,0.269693,54.8034)">
          |            <rect x="0.87" y="79.497" width="17.08" height="3.401" style="fill:none;"/>
          |            <g transform="matrix(0.715621,0,0,2.32599,-38.0352,123.069)">
          |                <path d="M54.4,-18L55.53,-18.05L56.67,-18.1L57.82,-18.14L58.98,-18.19L60.15,-18.24L62.51,-18.34L63.7,-18.39L66.1,-18.49L67.31,-18.46L68.51,-18.41L69.73,-18.36L70.94,-18.31L72.15,-18.25L73.36,-18.2L74.58,-18.15L75.79,-18.1L76.99,-18.05L78.2,-18L76.99,-17.95L75.79,-17.9L74.58,-17.85L73.36,-17.8L72.15,-17.75L70.94,-17.69L69.73,-17.64L68.51,-17.59L67.31,-17.54L66.1,-17.51L63.7,-17.61L62.51,-17.66L60.15,-17.76L58.98,-17.81L57.82,-17.86L56.67,-17.9L55.53,-17.95L54.4,-18Z" style="fill-rule:nonzero;"/>
          |            </g>
          |        </g>
          |    </g>
          |</svg>
          |""".stripMargin

      Some(() => parseSVG(taperedSVG))
    case EdgeStyle.invis => None
