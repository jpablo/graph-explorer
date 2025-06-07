package org.jpablo.graphexplorer.viewer.components.attributes.previews

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Shape

def ShapePreview(shape: Shape, w: Int = 100, h: Int = 20): Option[() => ReactiveSvgElement[dom.svg.SVG]] =
  shape match
    case Shape.box | Shape.rectangle | Shape.rect =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 16",
          svg.path(
            svg.d := "M22 1a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H2a1 1 0 0 1-1-1V2a1 1 0 0 1 1-1zM2 0a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h20a2 2 0 0 0 2-2V2a2 2 0 0 0-2-2z"
          )
        )
      )

    case Shape.ellipse | Shape.oval =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "328 -42 55 37",
          svg.ellipse(
            svg.cx          := "355.52",
            svg.cy          := "-23.65",
            svg.rx          := "27",
            svg.ry          := "18",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.circle =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "619 -48 49 49",
          svg.ellipse(
            svg.cx          := "643.52",
            svg.cy          := "-23.65",
            svg.rx          := "23.65",
            svg.ry          := "23.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.diamond =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "597 -150 93 37",
          svg.polygon(
            svg.points      := "643.52,-149.65 597.3,-131.65 643.52,-113.65 689.75,-131.65 643.52,-149.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.triangle =>
      Some(() =>
        svg.svg(
          svg.width  := w.toString,
          svg.height := h.toString,
          svg.polygon(
            svg.points := {
              val centerX = w / 2
              val topY = 2
              val bottomY = h - 2
              s"$centerX,$topY ${w - 2},$bottomY 2,$bottomY"
            },
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "1"
          )
        )
      )

    case Shape.invtriangle =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "425 -373 149 61",
          svg.polygon(
            svg.points      := "499.52,-312.65 573.59,-372.65 425.46,-372.65 499.52,-312.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.polygon | Shape.pentagon | Shape.hexagon | Shape.septagon | Shape.octagon =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "177 -88 89 80",
          svg.polygon(
            svg.points      := "265.07,-57.18 221.38,-87.37 177.7,-57.18 194.38,-8.34 248.38,-8.34 265.07,-57.18",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "3"
          )
        )
      )

    case Shape.doublecircle =>
      Some(() =>
        svg.svg(
          svg.width  := w.toString,
          svg.height := h.toString,
          svg.g(
            svg.circle(
              svg.cx          := (w / 2).toString,
              svg.cy          := (h / 2).toString,
              svg.r           := (Math.min(w, h) / 2 - 2).toString,
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.circle(
              svg.cx          := (w / 2).toString,
              svg.cy          := (h / 2).toString,
              svg.r           := (Math.min(w, h) / 2 - 6).toString,
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.doubleoctagon | Shape.tripleoctagon =>
      val rings = if shape == Shape.doubleoctagon then 2 else 3
      Some(() =>
        svg.svg(
          svg.width  := w.toString,
          svg.height := h.toString,
          svg.g(
            (0 until rings).map { i =>
              val padding = i * 4
              svg.polygon(
                svg.points := {
                  val centerX = w / 2
                  val centerY = h / 2
                  val radius = Math.min(w, h) / 2 - 2 - padding
                  val points = (0 until 8).map { j =>
                    val angle = j * 2 * Math.PI / 8
                    val x = centerX + radius * Math.cos(angle)
                    val y = centerY + radius * Math.sin(angle)
                    s"${x.round},${y.round}"
                  }
                  points.mkString(" ")
                },
                svg.stroke      := "currentColor",
                svg.fill        := "none",
                svg.strokeWidth := "2"
              )
            }
          )
        )
      )

    case Shape.point =>
      Some(() =>
        svg.svg(
          svg.width  := w.toString,
          svg.height := h.toString,
          svg.circle(
            svg.cx     := (w / 2).toString,
            svg.cy     := (h / 2).toString,
            svg.r      := "3",
            svg.stroke := "currentColor",
            svg.fill   := "currentColor"
          )
        )
      )

    case Shape.egg =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "38 -150 60 37",
          svg.polygon(
            svg.points := "70.18,-113.7 71.94,-113.8 73.69,-113.95 75.41,-114.14 77.09,-114.39 78.74,-114.68 80.34,-115.02 81.89,-115.4 83.38,-115.83 84.81,-116.3 86.16,-116.81 87.45,-117.37 88.66,-117.96 89.79,-118.59 90.83,-119.26 91.79,-119.96 92.65,-120.69 93.43,-121.45 94.11,-122.25 94.7,-123.06 95.19,-123.9 95.59,-124.76 95.9,-125.64 96.11,-126.54 96.23,-127.45 96.27,-128.37 96.21,-129.3 96.07,-130.24 95.85,-131.18 95.55,-132.12 95.18,-133.07 94.73,-134 94.21,-134.93 93.63,-135.86 92.99,-136.77 92.29,-137.66 91.54,-138.54 90.74,-139.41 89.89,-140.25 89.01,-141.06 88.08,-141.85 87.12,-142.62 86.13,-143.35 85.11,-144.05 84.07,-144.71 83,-145.35 81.92,-145.94 80.82,-146.49 79.7,-147.01 78.57,-147.48 77.43,-147.91 76.29,-148.29 75.13,-148.63 73.97,-148.92 72.8,-149.16 71.63,-149.36 70.46,-149.51 69.29,-149.6 68.11,-149.65 66.94,-149.65 65.76,-149.6 64.59,-149.51 63.41,-149.36 62.24,-149.16 61.08,-148.92 59.92,-148.63 58.76,-148.29 57.61,-147.91 56.48,-147.48 55.35,-147.01 54.23,-146.49 53.13,-145.94 52.05,-145.35 50.98,-144.71 49.94,-144.05 48.92,-143.35 47.93,-142.62 46.97,-141.85 46.04,-141.06 45.15,-140.25 44.31,-139.41 43.51,-138.54 42.75,-137.66 42.06,-136.77 41.41,-135.86 40.83,-134.93 40.32,-134 39.87,-133.07 39.49,-132.12 39.19,-131.18 38.97,-130.24 38.83,-129.3 38.78,-128.37 38.81,-127.45 38.94,-126.54 39.15,-125.64 39.46,-124.76 39.85,-123.9 40.35,-123.06 40.94,-122.25 41.62,-121.45 42.39,-120.69 43.26,-119.96 44.22,-119.26 45.26,-118.59 46.39,-117.96 47.6,-117.37 48.88,-116.81 50.24,-116.3 51.67,-115.83 53.16,-115.4 54.71,-115.02 56.31,-114.68 57.96,-114.39 59.64,-114.14 61.36,-113.95 63.1,-113.8 64.86,-113.7 66.64,-113.65 68.41,-113.65 70.18,-113.7",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.trapezium =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "736 -159 103 55",
          svg.polygon(
            svg.points      := "817.28,-158.65 757.77,-158.65 736.58,-104.65 838.47,-104.65 817.28,-158.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.invtrapezium =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "581 -375 124 55",
          svg.polygon(
            svg.points      := "607.42,-320.65 679.63,-320.65 705.34,-374.65 581.71,-374.65 607.42,-320.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.parallelogram =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 -267 136 55",
          svg.polygon(
            svg.points      := "135.05,-266.65 27.63,-266.65 0,-212.65 107.42,-212.65 135.05,-266.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.house =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "179 -262 65 42",
          svg.polygon(
            svg.points := "243.67,-246.18 211.52,-261.72 179.38,-246.18 179.41,-221.02 243.64,-221.02 243.67,-246.18",
            svg.stroke := "currentColor",
            svg.fill   := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.invhouse =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "745 -367 85 42",
          svg.polygon(
            svg.points := "745.88,-341.13 787.52,-325.58 829.17,-341.13 829.13,-366.29 745.92,-366.29 745.88,-341.13",
            svg.stroke := "currentColor",
            svg.fill   := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.cylinder =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "472 -582 55 37",
          svg.g(
            svg.path(
              svg.d := "M526.52,-578.38C526.52,-580.19 514.42,-581.65 499.52,-581.65 484.63,-581.65 472.52,-580.19 472.52,-578.38 472.52,-578.38 472.52,-548.93 472.52,-548.93 472.52,-547.12 484.63,-545.65 499.52,-545.65 514.42,-545.65 526.52,-547.12 526.52,-548.93 526.52,-548.93 526.52,-578.38 526.52,-578.38",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.path(
              svg.d := "M526.52,-578.38C526.52,-576.58 514.42,-575.11 499.52,-575.11 484.63,-575.11 472.52,-576.58 472.52,-578.38",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.note =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "616 -582 55 37",
          svg.g(
            svg.polygon(
              svg.points := "664.52,-581.65 616.52,-581.65 616.52,-545.65 670.52,-545.65 670.52,-575.65 664.52,-581.65",
              svg.stroke := "currentColor",
              svg.fill   := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "664.52,-581.65 664.52,-575.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "670.52,-575.65 664.52,-575.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.tab =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "760 -586 55 41",
          svg.g(
            svg.polygon(
              svg.points := "814.52,-581.65 772.52,-581.65 772.52,-585.65 760.52,-585.65 760.52,-545.65 814.52,-545.65 814.52,-581.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "760.52,-581.65 772.52,-581.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.folder =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "40 -694 55 41",
          svg.polygon(
            svg.points := "94.52,-689.65 91.52,-693.65 70.52,-693.65 67.52,-689.65 40.52,-689.65 40.52,-653.65 94.52,-653.65 94.52,-689.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.box3d =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "184 -690 55 37",
          svg.g(
            svg.polygon(
              svg.points := "238.52,-689.65 188.52,-689.65 184.52,-685.65 184.52,-653.65 234.52,-653.65 238.52,-657.65 238.52,-689.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "234.52,-685.65 184.52,-685.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "234.52,-685.65 234.52,-653.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "234.52,-685.65 238.52,-689.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.component =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "462 -690 71 37",
          svg.g(
            svg.polygon(
              svg.points := "532.26,-689.65 466.79,-689.65 466.79,-685.65 462.79,-685.65 462.79,-681.65 466.79,-681.65 466.79,-661.65 462.79,-661.65 462.79,-657.65 466.79,-657.65 466.79,-653.65 532.26,-653.65 532.26,-689.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "466.79,-685.65 470.79,-685.65 470.79,-681.65 466.79,-681.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "466.79,-661.65 470.79,-661.65 470.79,-657.65 466.79,-657.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.rarrow =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "328 -1014 55 37",
          svg.polygon(
            svg.points := "364.52,-1007.65 328.52,-1007.65 328.52,-983.65 364.52,-983.65 364.52,-977.65 382.52,-995.65 364.52,-1013.65 364.52,-1007.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.larrow =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "472 -1014 55 37",
          svg.polygon(
            svg.points := "526.52,-1007.65 490.52,-1007.65 490.52,-1013.65 472.52,-995.65 490.52,-977.65 490.52,-983.65 526.52,-983.65 526.52,-1007.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.lpromoter =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "614 -1014 59 37",
          svg.polygon(
            svg.points := "672.64,-1007.65 632.41,-1007.65 632.41,-1013.65 614.41,-995.65 632.41,-977.65 632.41,-983.65 654.64,-983.65 654.64,-977.65 672.64,-977.65 672.64,-1007.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.rpromoter =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "757 -1014 61 37",
          svg.polygon(
            svg.points := "799.2,-1007.65 757.85,-1007.65 757.85,-977.65 775.85,-977.65 775.85,-983.65 799.2,-983.65 799.2,-977.65 817.2,-995.65 799.2,-1013.65 799.2,-1007.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.promoter =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "757 -1014 61 37",
          svg.polygon(
            svg.points := "799.2,-1007.65 757.85,-1007.65 757.85,-977.65 775.85,-977.65 775.85,-983.65 799.2,-983.65 799.2,-977.65 817.2,-995.65 799.2,-1013.65 799.2,-1007.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.cds =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "760 -684 55 25",
          svg.polygon(
            svg.points := "802.52,-683.65 760.52,-683.65 760.52,-659.65 802.52,-659.65 814.52,-671.65 802.52,-683.65",
            svg.stroke := "currentColor",
            svg.fill   := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.terminator =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "37 -792 62 13",
          svg.g(
            svg.polygon(
              svg.points := "70.52,-779.65 70.52,-785.65 76.52,-785.65 76.52,-791.65 58.52,-791.65 58.52,-785.65 64.52,-785.65 64.52,-779.65 70.52,-779.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "37.02,-779.65 98.03,-779.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.utr =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "184 -789 55 10",
          svg.g(
            svg.polygon(
              svg.points := "220.52,-779.65 220.52,-782.65 214.52,-788.65 208.52,-788.65 202.52,-782.65 202.52,-779.65 220.52,-779.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "184.52,-779.65 238.52,-779.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.fivepoverhang =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "604 -788 79 16",
          svg.g(
            svg.polygon(
              svg.points      := "604.11,-781.15 628.11,-781.15 628.11,-787.15 604.11,-787.15 604.11,-781.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points      := "616.11,-772.15 628.11,-772.15 628.11,-778.15 616.11,-778.15 616.11,-772.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "628.11,-779.65 682.94,-779.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.primersite =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "325 -795 61 16",
          svg.g(
            svg.polygon(
              svg.points := "367.52,-782.65 355.52,-794.65 355.52,-788.65 340.55,-788.65 340.55,-782.65 367.52,-782.65",
              svg.stroke := "currentColor",
              svg.fill   := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "325.58,-779.65 385.47,-779.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.restrictionsite =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "462 -789 75 19",
          svg.g(
            svg.polygon(
              svg.points := "514.89,-782.65 490.16,-782.65 490.16,-788.65 484.16,-788.65 484.16,-776.65 508.89,-776.65 508.89,-770.65 514.89,-770.65 514.89,-782.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "462.07,-779.65 484.16,-779.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "514.89,-779.65 536.98,-779.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.threepoverhang =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "744 -788 87 16",
          svg.g(
            svg.polygon(
              svg.points      := "830.55,-781.15 830.55,-787.15 806.55,-787.15 806.55,-781.15 830.55,-781.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points      := "818.55,-772.15 818.55,-778.15 806.55,-778.15 806.55,-772.15 818.55,-772.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "744.5,-779.65 806.55,-779.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.noverhang =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "35 -896 65 17",
          svg.g(
            svg.polygon(
              svg.points      := "54.02,-889.15 66.02,-889.15 66.02,-895.15 54.02,-895.15 54.02,-889.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points      := "54.02,-880.15 66.02,-880.15 66.02,-886.15 54.02,-886.15 54.02,-880.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points      := "69.02,-880.15 81.02,-880.15 81.02,-886.15 69.02,-886.15 69.02,-880.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points      := "69.02,-889.15 81.02,-889.15 81.02,-895.15 69.02,-895.15 69.02,-889.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "81.02,-887.65 99.15,-887.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "54.02,-887.65 35.89,-887.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.assembly =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "182 -896 59 17",
          svg.g(
            svg.polygon(
              svg.points      := "199.52,-889.15 223.52,-889.15 223.52,-895.15 199.52,-895.15 199.52,-889.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polygon(
              svg.points      := "199.52,-880.15 223.52,-880.15 223.52,-886.15 199.52,-886.15 199.52,-880.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "223.52,-887.65 240.64,-887.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "182.41,-887.65 199.52,-887.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.signature =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "326 -900 59 25",
          svg.g(
            svg.polygon(
              svg.points      := "384.09,-899.65 326.96,-899.65 326.96,-875.65 384.09,-875.65 384.09,-899.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "329.96,-889.15 332.96,-886.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "329.96,-886.15 332.96,-889.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "329.96,-878.65 381.09,-878.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.insulator =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "472 -897 55 19",
          svg.g(
            svg.polygon(
              svg.points      := "505.52,-893.65 505.52,-881.65 493.52,-881.65 493.52,-893.65 505.52,-893.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "508.52,-896.65 508.52,-878.65 490.52,-878.65 490.52,-896.65 508.52,-896.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "508.52,-887.65 526.52,-887.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "472.52,-887.65 490.52,-887.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.ribosite =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "616 -900 55 13",
          svg.g(
            svg.polygon(
              svg.points := "646.52,-893.65 646.52,-895.15 645.02,-896.65 646.52,-898.15 646.52,-899.65 645.02,-899.65 643.52,-898.15 642.02,-899.65 640.52,-899.65 640.52,-898.15 642.02,-896.65 640.52,-895.15 640.52,-893.65 642.02,-893.65 643.52,-895.15 645.02,-893.65 646.52,-893.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "643.52,-887.65 643.52,-889.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "643.52,-890.65 643.52,-892.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "616.52,-887.65 670.52,-887.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.rnastab =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "760 -900 55 13",
          svg.g(
            svg.polygon(
              svg.points := "789.02,-893.65 790.52,-895.15 790.52,-898.15 789.02,-899.65 786.02,-899.65 784.52,-898.15 784.52,-895.15 786.02,-893.65 789.02,-893.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "787.52,-887.65 787.52,-889.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "787.52,-890.65 787.52,-892.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "760.52,-887.65 814.52,-887.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.proteasesite =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "32 -1008 71 13",
          svg.g(
            svg.polygon(
              svg.points := "70.52,-1001.65 70.52,-1003.15 69.02,-1004.65 70.52,-1006.15 70.52,-1007.65 69.02,-1007.65 67.52,-1006.15 66.02,-1007.65 64.52,-1007.65 64.52,-1006.15 66.02,-1004.65 64.52,-1003.15 64.52,-1001.65 66.02,-1001.65 67.52,-1003.15 69.02,-1001.65 70.52,-1001.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "67.52,-1003.15 67.52,-995.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "32.29,-995.65 102.76,-995.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.proteinstab =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "178 -1008 67 13",
          svg.g(
            svg.polygon(
              svg.points := "213.02,-1001.65 214.52,-1003.15 214.52,-1006.15 213.02,-1007.65 210.02,-1007.65 208.52,-1006.15 208.52,-1003.15 210.02,-1001.65 213.02,-1001.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "211.52,-1001.65 211.52,-995.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "178.79,-995.65 244.26,-995.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.square =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "44 -587 47 47",
          svg.polygon(
            svg.points      := "90.81,-586.94 44.24,-586.94 44.24,-540.37 90.81,-540.37 90.81,-586.94",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.star =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "166 -607 91 87",
          svg.polygon(
            svg.points := "256.06,-573.65 222.04,-573.65 211.52,-606.01 201.01,-573.65 166.98,-573.65 194.51,-553.65 184,-521.29 211.52,-541.29 239.05,-521.29 228.54,-553.65 256.06,-573.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.Mdiamond =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "14 -474 107 37",
          svg.g(
            svg.polygon(
              svg.points      := "67.52,-473.65 14.21,-455.65 67.52,-437.65 120.84,-455.65 67.52,-473.65",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "25.58,-459.49 25.58,-451.81",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "56.15,-441.49 78.89,-441.49",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "109.47,-451.81 109.47,-459.49",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "78.89,-469.81 56.15,-469.81",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.Msquare =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "184 -484 55 56",
          svg.g(
            svg.polygon(
              svg.points      := "238.98,-483.11 184.07,-483.11 184.07,-428.2 238.98,-428.2 238.98,-483.11",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "196.07,-483.11 184.07,-471.11",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "184.07,-440.2 196.07,-428.2",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "226.98,-428.2 238.98,-440.2",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "238.98,-471.11 226.98,-483.11",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.Mcircle =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "326 -485 60 60",
          svg.g(
            svg.ellipse(
              svg.cx          := "355.52",
              svg.cy          := "-455.65",
              svg.rx          := "28.66",
              svg.ry          := "28.66",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "374.48,-477.15 336.57,-477.15",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.polyline(
              svg.points      := "374.48,-434.16 336.57,-434.16",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.Mrecord =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "472 -474 55 37",
          svg.path(
            svg.d := "M484.52,-437.65C484.52,-437.65 514.52,-437.65 514.52,-437.65 520.52,-437.65 526.52,-443.65 526.52,-449.65 526.52,-449.65 526.52,-461.65 526.52,-461.65 526.52,-467.65 520.52,-473.65 514.52,-473.65 514.52,-473.65 484.52,-473.65 484.52,-473.65 478.52,-473.65 472.52,-467.65 472.52,-461.65 472.52,-461.65 472.52,-449.65 472.52,-449.65 472.52,-443.65 478.52,-437.65 484.52,-437.65",
            svg.stroke      := "currentColor",
            svg.fill        := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.none =>
      None

    case Shape.plain =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "497 -142 24 14",
          svg.g(
            svg.polygon(
              svg.points := "521.38,-142.44 497.38,-142.44 497.38,-128.04 521.38,-128.04 521.38,-142.44",
              svg.stroke := "none",
              svg.fill   := "lightgrey"
            ),
            svg.text(
              svg.x          := "509.38",
              svg.y          := "-132.64",
              svg.fontFamily := "Times,serif",
              svg.fontSize   := "10.00",
              svg.textAnchor := "middle",
              svg.fill       := "currentColor",
              "plain"
            )
          )
        )
      )

    case Shape.plaintext =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "336 -153 58 36",
          svg.g(
            svg.polygon(
              svg.points := "394.38,-153.24 336.39,-153.24 336.39,-117.24 394.38,-117.24 394.38,-153.24",
              svg.stroke := "none",
              svg.fill   := "lightgrey"
            ),
            svg.text(
              svg.x          := "365.38",
              svg.y          := "-132.64",
              svg.fontFamily := "Times,serif",
              svg.fontSize   := "10.00",
              svg.textAnchor := "middle",
              svg.fill       := "currentColor",
              "plaintext"
            )
          )
        )
      )

    case Shape.underline =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "338 -586 55 37",
          svg.g(
            svg.polygon(
              svg.points := "392.38,-585.24 338.38,-585.24 338.38,-549.24 392.38,-549.24 392.38,-585.24",
              svg.stroke := "none",
              svg.fill   := "none"
            ),
            svg.polyline(
              svg.points      := "338.38,-549.24 392.38,-549.24",
              svg.stroke      := "currentColor",
              svg.fill        := "none",
              svg.strokeWidth := "2"
            ),
            svg.text(
              svg.x          := "365.38",
              svg.y          := "-563.64",
              svg.fontFamily := "Times,serif",
              svg.fontSize   := "18.00",
              svg.textAnchor := "middle",
              svg.fill       := "currentColor",
              "u"
            )
          )
        )
      )
    case record => None
