package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.L.svg
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.Shape
import org.scalajs.dom.SVGSVGElement
import com.raquo.laminar.nodes.ReactiveSvgElement

def ShapePreview(shape: Shape, width: Int = 100, height: Int = 20): Option[() => ReactiveSvgElement[SVGSVGElement]] =
  shape match
    case Shape.box | Shape.rectangle | Shape.rect =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.polygon(
            svg.points := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              s"${x + w},$y $x,$y $x,${y + h} ${x + w},${y + h} ${x + w},$y"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.ellipse | Shape.oval =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.ellipse(
            svg.cx := (width / 2).toString,
            svg.cy := (height / 2).toString,
            svg.rx := ((width - 4) / 2).toString,
            svg.ry := ((height - 4) / 2).toString,
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.circle =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.circle(
            svg.cx := (width / 2).toString,
            svg.cy := (height / 2).toString,
            svg.r := (Math.min(width, height) / 2 - 2).toString,
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.diamond =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.polygon(
            svg.points := {
              val centerX = width / 2
              val centerY = height / 2
              val halfWidth = width / 2 - 2
              val halfHeight = height / 2 - 2
              s"$centerX,${centerY - halfHeight} ${centerX + halfWidth},$centerY $centerX,${centerY + halfHeight} ${centerX - halfWidth},$centerY"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.triangle =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.polygon(
            svg.points := {
              val centerX = width / 2
              val topY = 2
              val bottomY = height - 2
              s"$centerX,$topY ${width - 2},$bottomY 2,$bottomY"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.invtriangle =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.polygon(
            svg.points := {
              val centerX = width / 2
              val topY = 2
              s"2,$topY ${width - 2},$topY $centerX,${height - 2}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.polygon | Shape.pentagon | Shape.hexagon | Shape.septagon | Shape.octagon =>
      val sides = shape match
        case Shape.pentagon => 5
        case Shape.hexagon => 6
        case Shape.septagon => 7
        case Shape.octagon => 8
        case _ => 5 // default pentagon for polygon

      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.polygon(
            svg.points := {
              val centerX = width / 2
              val centerY = height / 2
              val radius = Math.min(width, height) / 2 - 2
              val points = (0 until sides).map { i =>
                val angle = i * 2 * Math.PI / sides - Math.PI / 2
                val x = centerX + radius * Math.cos(angle)
                val y = centerY + radius * Math.sin(angle)
                s"${x.round},${y.round}"
              }
              points.mkString(" ")
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.doublecircle =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.g(
            svg.circle(
              svg.cx := (width / 2).toString,
              svg.cy := (height / 2).toString,
              svg.r := (Math.min(width, height) / 2 - 2).toString,
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.circle(
              svg.cx := (width / 2).toString,
              svg.cy := (height / 2).toString,
              svg.r := (Math.min(width, height) / 2 - 6).toString,
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.doubleoctagon | Shape.tripleoctagon =>
      val rings = if shape == Shape.doubleoctagon then 2 else 3
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.g(
            (0 until rings).map { i =>
              val padding = i * 4
              svg.polygon(
                svg.points := {
                  val centerX = width / 2
                  val centerY = height / 2
                  val radius = Math.min(width, height) / 2 - 2 - padding
                  val points = (0 until 8).map { j =>
                    val angle = j * 2 * Math.PI / 8
                    val x = centerX + radius * Math.cos(angle)
                    val y = centerY + radius * Math.sin(angle)
                    s"${x.round},${y.round}"
                  }
                  points.mkString(" ")
                },
                svg.stroke := "currentColor",
                svg.fill := "none",
                svg.strokeWidth := "2"
              )
            }
          )
        )
      )

    case Shape.point =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.circle(
            svg.cx := (width / 2).toString,
            svg.cy := (height / 2).toString,
            svg.r := "3",
            svg.stroke := "currentColor",
            svg.fill := "currentColor"
          )
        )
      )

    case Shape.egg =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "38 -150 60 37",
          svg.polygon(
            svg.points := "70.18,-113.7 71.94,-113.8 73.69,-113.95 75.41,-114.14 77.09,-114.39 78.74,-114.68 80.34,-115.02 81.89,-115.4 83.38,-115.83 84.81,-116.3 86.16,-116.81 87.45,-117.37 88.66,-117.96 89.79,-118.59 90.83,-119.26 91.79,-119.96 92.65,-120.69 93.43,-121.45 94.11,-122.25 94.7,-123.06 95.19,-123.9 95.59,-124.76 95.9,-125.64 96.11,-126.54 96.23,-127.45 96.27,-128.37 96.21,-129.3 96.07,-130.24 95.85,-131.18 95.55,-132.12 95.18,-133.07 94.73,-134 94.21,-134.93 93.63,-135.86 92.99,-136.77 92.29,-137.66 91.54,-138.54 90.74,-139.41 89.89,-140.25 89.01,-141.06 88.08,-141.85 87.12,-142.62 86.13,-143.35 85.11,-144.05 84.07,-144.71 83,-145.35 81.92,-145.94 80.82,-146.49 79.7,-147.01 78.57,-147.48 77.43,-147.91 76.29,-148.29 75.13,-148.63 73.97,-148.92 72.8,-149.16 71.63,-149.36 70.46,-149.51 69.29,-149.6 68.11,-149.65 66.94,-149.65 65.76,-149.6 64.59,-149.51 63.41,-149.36 62.24,-149.16 61.08,-148.92 59.92,-148.63 58.76,-148.29 57.61,-147.91 56.48,-147.48 55.35,-147.01 54.23,-146.49 53.13,-145.94 52.05,-145.35 50.98,-144.71 49.94,-144.05 48.92,-143.35 47.93,-142.62 46.97,-141.85 46.04,-141.06 45.15,-140.25 44.31,-139.41 43.51,-138.54 42.75,-137.66 42.06,-136.77 41.41,-135.86 40.83,-134.93 40.32,-134 39.87,-133.07 39.49,-132.12 39.19,-131.18 38.97,-130.24 38.83,-129.3 38.78,-128.37 38.81,-127.45 38.94,-126.54 39.15,-125.64 39.46,-124.76 39.85,-123.9 40.35,-123.06 40.94,-122.25 41.62,-121.45 42.39,-120.69 43.26,-119.96 44.22,-119.26 45.26,-118.59 46.39,-117.96 47.6,-117.37 48.88,-116.81 50.24,-116.3 51.67,-115.83 53.16,-115.4 54.71,-115.02 56.31,-114.68 57.96,-114.39 59.64,-114.14 61.36,-113.95 63.1,-113.8 64.86,-113.7 66.64,-113.65 68.41,-113.65 70.18,-113.7",
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.trapezium =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.polygon(
            svg.points := {
              val margin = width * 0.2
              s"$margin,${height - 2} ${width - margin},${height - 2} ${width - 2},2 2,2"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.invtrapezium =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.polygon(
            svg.points := {
              val margin = width * 0.2
              s"2,${height - 2} ${width - 2},${height - 2} ${width - margin},2 $margin,2"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.parallelogram =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.polygon(
            svg.points := {
              val skew = width * 0.2
              s"${skew},${height - 2} ${width - 2},${height - 2} ${width - skew},2 2,2"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.house =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.polygon(
            svg.points := {
              val centerX = width / 2
              val topY = 2
              val midY = height * 0.4
              val bottomY = height - 2
              s"$centerX,$topY ${width - 2},$midY ${width - 2},$bottomY 2,$bottomY 2,$midY"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.invhouse =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.polygon(
            svg.points := {
              val centerX = width / 2
              val topY = 2
              val midY = height * 0.6
              val bottomY = height - 2
              s"2,$topY ${width - 2},$topY ${width - 2},$midY $centerX,$bottomY 2,$midY"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.cylinder =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "472 -582 55 37",
          svg.g(
            svg.path(
              svg.d := "M526.52,-578.38C526.52,-580.19 514.42,-581.65 499.52,-581.65 484.63,-581.65 472.52,-580.19 472.52,-578.38 472.52,-578.38 472.52,-548.93 472.52,-548.93 472.52,-547.12 484.63,-545.65 499.52,-545.65 514.42,-545.65 526.52,-547.12 526.52,-548.93 526.52,-548.93 526.52,-578.38 526.52,-578.38",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.path(
              svg.d := "M526.52,-578.38C526.52,-576.58 514.42,-575.11 499.52,-575.11 484.63,-575.11 472.52,-576.58 472.52,-578.38",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.note =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "616 -582 55 37",
          svg.g(
            svg.polygon(
              svg.points := "664.52,-581.65 616.52,-581.65 616.52,-545.65 670.52,-545.65 670.52,-575.65 664.52,-581.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "664.52,-581.65 664.52,-575.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "670.52,-575.65 664.52,-575.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.tab =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "760 -586 55 41",
          svg.g(
            svg.polygon(
              svg.points := "814.52,-581.65 772.52,-581.65 772.52,-585.65 760.52,-585.65 760.52,-545.65 814.52,-545.65 814.52,-581.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "760.52,-581.65 772.52,-581.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.folder =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "40 -694 55 41",
          svg.polygon(
            svg.points := "94.52,-689.65 91.52,-693.65 70.52,-693.65 67.52,-689.65 40.52,-689.65 40.52,-653.65 94.52,-653.65 94.52,-689.65",
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.box3d =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "184 -690 55 37",
          svg.g(
            svg.polygon(
              svg.points := "238.52,-689.65 188.52,-689.65 184.52,-685.65 184.52,-653.65 234.52,-653.65 238.52,-657.65 238.52,-689.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "234.52,-685.65 184.52,-685.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "234.52,-685.65 234.52,-653.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "234.52,-685.65 238.52,-689.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.component =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "462 -690 71 37",
          svg.g(
            svg.polygon(
              svg.points := "532.26,-689.65 466.79,-689.65 466.79,-685.65 462.79,-685.65 462.79,-681.65 466.79,-681.65 466.79,-661.65 462.79,-661.65 462.79,-657.65 466.79,-657.65 466.79,-653.65 532.26,-653.65 532.26,-689.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "466.79,-685.65 470.79,-685.65 470.79,-681.65 466.79,-681.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "466.79,-661.65 470.79,-661.65 470.79,-657.65 466.79,-657.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.promoter | Shape.lpromoter | Shape.rpromoter =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "615 -693 57 22",
          svg.g(
            svg.polygon(
              svg.points := "650.53,-689.65 629.52,-689.65 629.52,-671.65 635.52,-671.65 635.52,-683.65 650.53,-683.65 650.53,-680.65 662.53,-686.65 650.53,-692.65 650.53,-689.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "615.52,-671.65 671.53,-671.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.cds =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val arrow = Math.min(w, h) * 0.2
              s"M$x,${y + h/2} " +
                s"L${x + w - arrow},${y + h/2} " +
                s"L${x + w},${y} " +
                s"L${x + w},${y + h} " +
                s"L${x + w - arrow},${y + h/2}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.terminator =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val stemWidth = Math.min(w, h) * 0.1
              s"M${x + w/2 - stemWidth/2},${y + h} " +
                s"L${x + w/2 + stemWidth/2},${y + h} " +
                s"L${x + w/2 + stemWidth/2},${y + h/2} " +
                s"L${x + w},${y + h/2} " +
                s"M${x + w/2 - stemWidth/2},${y + h/2} " +
                s"L$x,${y + h/2}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.utr =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val boxSize = Math.min(w, h) * 0.2
              s"M${x + boxSize},${y + h} " +
                s"L${x + boxSize},${y + h - boxSize} " +
                s"L${x + boxSize * 2},${y + h - boxSize} " +
                s"L${x + boxSize * 2},${y + h}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.primersite =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val arrowSize = Math.min(w, h) * 0.3
              s"M${x + w/2},${y + h} " +
                s"L${x + w/2 - arrowSize},${y} " +
                s"L${x + w/2 + arrowSize},${y} Z"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.restrictionsite =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val cutSize = Math.min(w, h) * 0.2
              s"M${x + w/2 - cutSize},${y + h} " +
                s"L${x + w/2 + cutSize},${y + h} " +
                s"M${x + w/2},${y + h} " +
                s"L${x + w/2},${y + h/2} " +
                s"L${x + w},${y + h/2} " +
                s"M$x,${y + h/2} " +
                s"L${x + w/2},${y + h/2}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.fivepoverhang | Shape.threepoverhang =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val boxWidth = w * 0.2
              val boxHeight = h * 0.3
              if shape == Shape.fivepoverhang then
                s"M${x + boxWidth},${y + h - boxHeight} " +
                  s"L${x + boxWidth * 2},${y + h - boxHeight} " +
                  s"L${x + boxWidth * 2},${y + h} " +
                  s"L${x + boxWidth},${y + h} Z"
              else
                s"M${x + w - boxWidth * 2},${y + h - boxHeight} " +
                  s"L${x + w - boxWidth},${y + h - boxHeight} " +
                  s"L${x + w - boxWidth},${y + h} " +
                  s"L${x + w - boxWidth * 2},${y + h} Z"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.noverhang =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val boxSize = Math.min(w, h) * 0.2
              s"M${x + boxSize},${y + h - boxSize} " +
                s"L${x + boxSize * 2},${y + h - boxSize} " +
                s"L${x + boxSize * 2},${y + h} " +
                s"L${x + boxSize},${y + h} Z " +
                s"M${x + boxSize * 3},${y + h - boxSize} " +
                s"L${x + boxSize * 4},${y + h - boxSize} " +
                s"L${x + boxSize * 4},${y + h} " +
                s"L${x + boxSize * 3},${y + h} Z"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.assembly =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val boxSize = Math.min(w, h) * 0.2
              s"M${x + boxSize},${y + h - boxSize} " +
                s"L${x + boxSize * 2},${y + h - boxSize} " +
                s"L${x + boxSize * 2},${y + h} " +
                s"L${x + boxSize},${y + h} Z " +
                s"M${x + w/2},${y + h - boxSize} " +
                s"L${x + w/2},${y + h}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.signature =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val boxSize = Math.min(w, h) * 0.2
              s"M${x + w/2 - boxSize},${y + h - boxSize} " +
                s"L${x + w/2 + boxSize},${y + h - boxSize} " +
                s"L${x + w/2 + boxSize},${y + h} " +
                s"L${x + w/2 - boxSize},${y + h} Z " +
                s"M${x + w/2 - boxSize/2},${y + h - boxSize} " +
                s"L${x + w/2 + boxSize/2},${y + h} " +
                s"M${x + w/2 - boxSize/2},${y + h} " +
                s"L${x + w/2 + boxSize/2},${y + h - boxSize}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.insulator =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val boxSize = Math.min(w, h) * 0.2
              s"M${x + w/2},${y + h - boxSize} " +
                s"L${x + w/2},${y + h} " +
                s"M${x + w/2 - boxSize},${y + h - boxSize} " +
                s"L${x + w/2 + boxSize},${y + h - boxSize} " +
                s"L${x + w/2 + boxSize},${y + h} " +
                s"L${x + w/2 - boxSize},${y + h} Z"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.ribosite =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val crossSize = Math.min(w, h) * 0.1
              s"M${x + w/2 - crossSize},${y + h - crossSize * 2} " +
                s"L${x + w/2 + crossSize},${y + h} " +
                s"M${x + w/2 - crossSize},${y + h} " +
                s"L${x + w/2 + crossSize},${y + h - crossSize * 2} " +
                s"M${x + w/2},${y + h} " +
                s"L${x + w/2},${y + h - crossSize * 2}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.rnastab =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val crossSize = Math.min(w, h) * 0.1
              s"M${x + w/2 - crossSize},${y + h - crossSize * 2} " +
                s"L${x + w/2 + crossSize},${y + h} " +
                s"M${x + w/2 - crossSize},${y + h} " +
                s"L${x + w/2 + crossSize},${y + h - crossSize * 2} " +
                s"M${x + w/2},${y + h} " +
                s"L${x + w/2},${y + h - crossSize * 2} " +
                s"M${x + w/2 - crossSize},${y + h - crossSize} " +
                s"L${x + w/2 + crossSize},${y + h - crossSize}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.proteasesite | Shape.proteinstab =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val crossSize = Math.min(w, h) * 0.1
              val d = if shape == Shape.proteasesite then
                s"M${x + w/2 - crossSize},${y + h - crossSize * 2} " +
                  s"L${x + w/2 + crossSize},${y + h} " +
                  s"M${x + w/2 - crossSize},${y + h} " +
                  s"L${x + w/2 + crossSize},${y + h - crossSize * 2} " +
                  s"M${x + w/2},${y + h} " +
                  s"L${x + w/2},${y + h - crossSize * 3}"
              else
                s"M${x + w/2 - crossSize},${y + h - crossSize * 2} " +
                  s"L${x + w/2 + crossSize},${y + h} " +
                  s"M${x + w/2 - crossSize},${y + h} " +
                  s"L${x + w/2 + crossSize},${y + h - crossSize * 2} " +
                  s"M${x + w/2},${y + h} " +
                  s"L${x + w/2},${y + h - crossSize * 3} " +
                  s"M${x + w/2 - crossSize},${y + h - crossSize} " +
                  s"L${x + w/2 + crossSize},${y + h - crossSize}"
              d
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.rarrow | Shape.larrow =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val arrowSize = Math.min(w, h) * 0.3
              if shape == Shape.rarrow then
                s"M$x,${y + h/2} " +
                  s"L${x + w - arrowSize},${y + h/2} " +
                  s"L${x + w - arrowSize},${y} " +
                  s"L${x + w},${y + h/2} " +
                  s"L${x + w - arrowSize},${y + h} " +
                  s"L${x + w - arrowSize},${y + h/2}"
              else
                s"M${x + w},${y + h/2} " +
                  s"L${x + arrowSize},${y + h/2} " +
                  s"L${x + arrowSize},${y} " +
                  s"L$x,${y + h/2} " +
                  s"L${x + arrowSize},${y + h} " +
                  s"L${x + arrowSize},${y + h/2}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.square =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "44 -587 47 47",
          svg.polygon(
            svg.points := "90.81,-586.94 44.24,-586.94 44.24,-540.37 90.81,-540.37 90.81,-586.94",
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.star =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "166 -607 91 87",
          svg.polygon(
            svg.points := "256.06,-573.65 222.04,-573.65 211.52,-606.01 201.01,-573.65 166.98,-573.65 194.51,-553.65 184,-521.29 211.52,-541.29 239.05,-521.29 228.54,-553.65 256.06,-573.65",
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.Mdiamond =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "14 -474 107 37",
          svg.g(
            svg.polygon(
              svg.points := "67.52,-473.65 14.21,-455.65 67.52,-437.65 120.84,-455.65 67.52,-473.65",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "25.58,-459.49 25.58,-451.81",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "56.15,-441.49 78.89,-441.49",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "109.47,-451.81 109.47,-459.49",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "78.89,-469.81 56.15,-469.81",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.Msquare =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "184 -484 55 56",
          svg.g(
            svg.polygon(
              svg.points := "238.98,-483.11 184.07,-483.11 184.07,-428.2 238.98,-428.2 238.98,-483.11",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "196.07,-483.11 184.07,-471.11",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "184.07,-440.2 196.07,-428.2",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "226.98,-428.2 238.98,-440.2",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "238.98,-471.11 226.98,-483.11",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.Mcircle =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "326 -485 60 60",
          svg.g(
            svg.ellipse(
              svg.cx := "355.52",
              svg.cy := "-455.65",
              svg.rx := "28.66",
              svg.ry := "28.66",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "374.48,-477.15 336.57,-477.15",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points := "374.48,-434.16 336.57,-434.16",
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.Mrecord =>
      Some(() =>
        svg.svg(
          svg.width := width.toString,
          svg.height := height.toString,
          svg.viewBox := "472 -474 55 37",
          svg.path(
            svg.d := "M484.52,-437.65C484.52,-437.65 514.52,-437.65 514.52,-437.65 520.52,-437.65 526.52,-443.65 526.52,-449.65 526.52,-449.65 526.52,-461.65 526.52,-461.65 526.52,-467.65 520.52,-473.65 514.52,-473.65 514.52,-473.65 484.52,-473.65 484.52,-473.65 478.52,-473.65 472.52,-467.65 472.52,-461.65 472.52,-461.65 472.52,-449.65 472.52,-449.65 472.52,-443.65 478.52,-437.65 484.52,-437.65",
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.plaintext | Shape.plain | Shape.none =>
      None

    case _ => None 