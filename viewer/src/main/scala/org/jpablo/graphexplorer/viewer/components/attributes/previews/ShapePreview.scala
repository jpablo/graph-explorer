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
          svg.polygon(
            svg.cls    := "node-preview",
            svg.points := "2,2 22,2 22,14 2,14 2,2"
          )
        )
      )

    case Shape.square =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 24",
          svg.polygon(
            svg.cls    := "node-preview",
            svg.points := "2,2 22,2 22,22 2,22 2,2"
          )
        )
      )

    case Shape.circle =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 24", // Normalized viewBox
          svg.ellipse( // Graphviz uses ellipse for circle
            svg.cls    := "node-preview",
            svg.cx    := "12",        // Center X in 24x24
            svg.cy    := "12",        // Center Y in 24x24
            svg.rx    := "11",        // Radius (24/2 - 1 for padding)
            svg.ry    := "11"         // Radius (24/2 - 1 for padding)
          )
        )
      )

    case Shape.ellipse | Shape.oval =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 16", // Normalized viewBox
          svg.ellipse(
            svg.cls    := "node-preview",
            svg.cx := "12", // Center X in 24x16
            svg.cy := "8",  // Center Y in 24x16
            svg.rx := "11", // Radius X (24/2 - 1 for padding)
            svg.ry := "7"   // Radius Y (16/2 - 1 for padding)
          )
        )
      )

    case Shape.point =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 24",
          svg.circle(
            svg.cls    := "node-preview",
            svg.cx := "12",
            svg.cy := "12",
            svg.r  := "3" // Small radius for a point
          )
        )
      )

    case Shape.none =>
      None

    case Shape.polygon | Shape.pentagon | Shape.hexagon | Shape.septagon | Shape.octagon =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 22",
          svg.polygon(
            svg.cls    := "node-preview",
            svg.points := "23.41,8.29 12,0 0.59,8.29 4.95,21.71 19.05,21.71"
          )
        )
      )

    case Shape.triangle =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 16", // Normalized viewBox
          svg.polygon(
            svg.cls    := "node-preview",
            svg.points := "12,2 22,14 2,14 12,2" // Points for triangle in 24x16
          )
        )
      )

    case Shape.diamond =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 16", // Normalized viewBox
          svg.polygon(
            svg.cls    := "node-preview",
            svg.points := "12,2 2,8 12,14 22,8 12,2" // Points for diamond in 24x16
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
            svg.cls    := "node-preview",
            svg.points := "256.06,-573.65 222.04,-573.65 211.52,-606.01 201.01,-573.65 166.98,-573.65 194.51,-553.65 184,-521.29 211.52,-541.29 239.05,-521.29 228.54,-553.65 256.06,-573.65"
          )
        )
      )

    case Shape.invtriangle =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 16", // Normalized viewBox
          svg.polygon(
            svg.cls    := "node-preview",
            svg.points := "12,14 22,2 2,2 12,14" // Points for inverted triangle in 24x16
          )
        )
      )

    case Shape.invtrapezium => // Bottom base shorter than top base
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 16", // Normalized viewBox
          svg.polygon(
            svg.cls    := "node-preview",
            svg.points := "2,2 22,2 19,14 5,14 2,2"
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
            svg.cls    := "node-preview",
            svg.points := "745.88,-341.13 787.52,-325.58 829.17,-341.13 829.13,-366.29 745.92,-366.29 745.88,-341.13"
          )
        )
      )

    case Shape.trapezium => // Top base shorter than bottom base
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 16", // Normalized viewBox
          svg.polygon(
            svg.cls    := "node-preview",
            svg.points := "5,2 19,2 22,14 2,14 5,2"
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
            svg.cls    := "node-preview",
            svg.points := "135.05,-266.65 27.63,-266.65 0,-212.65 107.42,-212.65 135.05,-266.65"
          )
        )
      )

    case Shape.house =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "-0.5 0 25 16",
          svg.polygon(
            svg.cls    := "node-preview",
            svg.points := "23.85,5.92 12,0 0.15,5.92 0.16,15.52 23.84,15.52 23.85,5.92"
          )
        )
      )

    case Shape.doublecircle =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 24", // Normalized viewBox
          svg.g(
            svg.circle(
              svg.cls    := "node-preview",
              svg.cx := "12",
              svg.cy := "12",
              svg.r  := "11" // Outer circle (24/2 - 1)
            ),
            svg.circle(
              svg.cls    := "node-preview",
              svg.cx := "12",
              svg.cy := "12",
              svg.r  := "8" // Inner circle (11 - 3 for spacing)
            )
          )
        )
      )

    case Shape.doubleoctagon | Shape.tripleoctagon =>
      val rings = if shape == Shape.doubleoctagon then 2 else 3
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 24", // Normalized viewBox
          svg.g(
            (0 until rings).map { i =>
              val centerX     = 12.0
              val centerY     = 12.0
              val ringSpacing = 2.0          // Spacing between octagon rings in viewBox units
              val baseRadius  = 11.0         // Max radius (24/2 - 1) for the outermost ring
              val radius      = baseRadius - (i * ringSpacing)
              val sides       = 8
              val angleOffset = -Math.PI / 2 // Point-up orientation

              svg.polygon(
                svg.cls    := "node-preview",
                svg.points := {
                  (0 until sides).map { j =>
                    val angle = angleOffset + j * 2 * Math.PI / sides
                    val x     = centerX + radius * Math.cos(angle)
                    val y     = centerY + radius * Math.sin(angle)
                    s"${(x * 10).round / 10.0},${(y * 10).round / 10.0}"
                  }.mkString(" ")
                }
              )
            }
          )
        )
      )

    case Shape.Mdiamond =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 16",
          svg.g(
            svg.polygon(svg.cls    := "node-preview", svg.points  := "11.84,0.55 0,7.45 11.84,14.36 23.68,7.45 11.84,0.55"),
            svg.polyline(svg.cls    := "node-preview", svg.points := "4.13,4.91 4.13,10"),
            svg.polyline(svg.cls    := "node-preview", svg.points := "7.74,12.18 15.94,12.18"),
            svg.polyline(svg.cls    := "node-preview", svg.points := "19.55,10 19.55,4.91"),
            svg.polyline(svg.cls    := "node-preview", svg.points := "15.94,2.64 7.74,2.64")
          )
        )
      )

    case Shape.Msquare =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 25",
          svg.g(
            svg.polygon(
              svg.cls    := "node-preview",
              svg.points := "24,0.39 0,0.39 0,24.39 24,24.39 24,0.39"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "5.24,0.39 0,5.63"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "0,19.15 5.24,24.39"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "18.76,24.39 24,19.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "24,5.63 18.76,0.39"
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
              svg.cls    := "node-preview",
              svg.cx := "355.52",
              svg.cy := "-455.65",
              svg.rx := "28.66",
              svg.ry := "28.66"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "374.48,-477.15 336.57,-477.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "374.48,-434.16 336.57,-434.16"
            )
          )
        )
      )

    case Shape.Mrecord =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 20 16",
          svg.path(
            svg.cls    := "node-preview",
            svg.d := "M5.24,15.89C5.24,15.89 13.96,15.89 13.96,15.89 16.58,15.89 18.76,13.71 18.76,11.09 18.76,11.09 18.76,5.51 18.76,5.51 18.76,2.89 16.58,0.71 13.96,0.71 13.96,0.71 5.24,0.71 5.24,0.71 2.62,0.71 0.44,2.89 0.44,5.51 0.44,5.51 0.44,11.09 0.44,11.09 0.44,13.71 2.62,15.89 5.24,15.89"
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
            svg.cls    := "node-preview",
            svg.points := "70.18,-113.7 71.94,-113.8 75.41,-114.14 78.74,-114.68 81.89,-115.4 84.81,-116.3 87.45,-117.37 89.79,-118.59 91.79,-119.96 93.43,-121.45 94.7,-123.06 95.59,-124.76 96.11,-126.54 96.27,-128.37 96.07,-130.24 95.55,-132.12 94.73,-134 93.63,-135.86 92.29,-137.66 90.74,-139.41 89.01,-141.06 87.12,-142.62 85.11,-144.05 83,-145.35 80.82,-146.49 78.57,-147.48 76.29,-148.29 73.97,-148.92 71.63,-149.36 69.29,-149.6 66.94,-149.65 64.59,-149.51 62.24,-149.16 59.92,-148.63 57.61,-147.91 55.35,-147.01 53.13,-145.94 50.98,-144.71 48.92,-143.35 46.97,-141.85 45.15,-140.25 43.51,-138.54 42.06,-136.77 40.83,-134.93 39.87,-133.07 39.19,-131.18 38.83,-129.3 38.81,-127.45 39.15,-125.64 39.85,-123.9 40.94,-122.25 42.39,-120.69 44.22,-119.26 46.39,-117.96 48.88,-116.81 51.67,-115.83 54.71,-115.02 57.96,-114.39 61.36,-113.95 64.86,-113.7 68.41,-113.65"
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
              svg.cls    := "node-preview",
              svg.d := "M526.52,-578.38C526.52,-580.19 514.42,-581.65 499.52,-581.65 484.63,-581.65 472.52,-580.19 472.52,-578.38 472.52,-578.38 472.52,-548.93 472.52,-548.93 472.52,-547.12 484.63,-545.65 499.52,-545.65 514.42,-545.65 526.52,-547.12 526.52,-548.93 526.52,-548.93 526.52,-578.38 526.52,-578.38"
            ),
            svg.path(
              svg.cls    := "node-preview",
              svg.d := "M526.52,-578.38C526.52,-576.58 514.42,-575.11 499.52,-575.11 484.63,-575.11 472.52,-576.58 472.52,-578.38"
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
              svg.cls    := "node-preview",
              svg.points := "664.52,-581.65 616.52,-581.65 616.52,-545.65 670.52,-545.65 670.52,-575.65 664.52,-581.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "664.52,-581.65 664.52,-575.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "670.52,-575.65 664.52,-575.65"
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
              svg.cls    := "node-preview",
              svg.points := "814.52,-581.65 772.52,-581.65 772.52,-585.65 760.52,-585.65 760.52,-545.65 814.52,-545.65 814.52,-581.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "760.52,-581.65 772.52,-581.65"
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
            svg.cls    := "node-preview",
            svg.points := "94.52,-689.65 91.52,-693.65 70.52,-693.65 67.52,-689.65 40.52,-689.65 40.52,-653.65 94.52,-653.65 94.52,-689.65"
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
              svg.cls    := "node-preview",
              svg.points := "238.52,-689.65 188.52,-689.65 184.52,-685.65 184.52,-653.65 234.52,-653.65 238.52,-657.65 238.52,-689.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "234.52,-685.65 184.52,-685.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "234.52,-685.65 234.52,-653.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "234.52,-685.65 238.52,-689.65"
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
              svg.cls    := "node-preview",
              svg.points := "532.26,-689.65 466.79,-689.65 466.79,-685.65 462.79,-685.65 462.79,-681.65 466.79,-681.65 466.79,-661.65 462.79,-661.65 462.79,-657.65 466.79,-657.65 466.79,-653.65 532.26,-653.65 532.26,-689.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "466.79,-685.65 470.79,-685.65 470.79,-681.65 466.79,-681.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "466.79,-661.65 470.79,-661.65 470.79,-657.65 466.79,-657.65"
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
            svg.cls    := "node-preview",
            svg.points := "364.52,-1007.65 328.52,-1007.65 328.52,-983.65 364.52,-983.65 364.52,-977.65 382.52,-995.65 364.52,-1013.65 364.52,-1007.65"
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
            svg.cls    := "node-preview",
            svg.points := "526.52,-1007.65 490.52,-1007.65 490.52,-1013.65 472.52,-995.65 490.52,-977.65 490.52,-983.65 526.52,-983.65 526.52,-1007.65"
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
            svg.cls    := "node-preview",
            svg.points := "672.64,-1007.65 632.41,-1007.65 632.41,-1013.65 614.41,-995.65 632.41,-977.65 632.41,-983.65 654.64,-983.65 654.64,-977.65 672.64,-977.65 672.64,-1007.65"
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
            svg.cls    := "node-preview",
            svg.points := "799.2,-1007.65 757.85,-1007.65 757.85,-977.65 775.85,-977.65 775.85,-983.65 799.2,-983.65 799.2,-977.65 817.2,-995.65 799.2,-1013.65 799.2,-1007.65"
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
            svg.cls    := "node-preview",
            svg.points := "799.2,-1007.65 757.85,-1007.65 757.85,-977.65 775.85,-977.65 775.85,-983.65 799.2,-983.65 799.2,-977.65 817.2,-995.65 799.2,-1013.65 799.2,-1007.65"
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
            svg.cls    := "node-preview",
            svg.points := "802.52,-683.65 760.52,-683.65 760.52,-659.65 802.52,-659.65 814.52,-671.65 802.52,-683.65"
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
              svg.cls    := "node-preview",
              svg.points := "70.52,-779.65 70.52,-785.65 76.52,-785.65 76.52,-791.65 58.52,-791.65 58.52,-785.65 64.52,-785.65 64.52,-779.65 70.52,-779.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "37.02,-779.65 98.03,-779.65"
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
              svg.cls    := "node-preview",
              svg.points := "220.52,-779.65 220.52,-782.65 214.52,-788.65 208.52,-788.65 202.52,-782.65 202.52,-779.65 220.52,-779.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "184.52,-779.65 238.52,-779.65"
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
              svg.cls    := "node-preview",
              svg.points := "604.11,-781.15 628.11,-781.15 628.11,-787.15 604.11,-787.15 604.11,-781.15"
            ),
            svg.polygon(
              svg.cls    := "node-preview",
              svg.points := "616.11,-772.15 628.11,-772.15 628.11,-778.15 616.11,-778.15 616.11,-772.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "628.11,-779.65 682.94,-779.65"
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
              svg.cls    := "node-preview",
              svg.points := "367.52,-782.65 355.52,-794.65 355.52,-788.65 340.55,-788.65 340.55,-782.65 367.52,-782.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "325.58,-779.65 385.47,-779.65"
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
              svg.cls    := "node-preview",
              svg.points := "514.89,-782.65 490.16,-782.65 490.16,-788.65 484.16,-788.65 484.16,-776.65 508.89,-776.65 508.89,-770.65 514.89,-770.65 514.89,-782.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "462.07,-779.65 484.16,-779.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "514.89,-779.65 536.98,-779.65"
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
              svg.cls    := "node-preview",
              svg.points := "830.55,-781.15 830.55,-787.15 806.55,-787.15 806.55,-781.15 830.55,-781.15"
            ),
            svg.polygon(
              svg.cls    := "node-preview",
              svg.points := "818.55,-772.15 818.55,-778.15 806.55,-778.15 806.55,-772.15 818.55,-772.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "744.5,-779.65 806.55,-779.65"
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
              svg.cls    := "node-preview",
              svg.points := "54.02,-889.15 66.02,-889.15 66.02,-895.15 54.02,-895.15 54.02,-889.15"
            ),
            svg.polygon(
              svg.cls    := "node-preview",
              svg.points := "54.02,-880.15 66.02,-880.15 66.02,-886.15 54.02,-886.15 54.02,-880.15"
            ),
            svg.polygon(
              svg.cls    := "node-preview",
              svg.points := "69.02,-880.15 81.02,-880.15 81.02,-886.15 69.02,-886.15 69.02,-880.15"
            ),
            svg.polygon(
              svg.cls    := "node-preview",
              svg.points := "69.02,-889.15 81.02,-889.15 81.02,-895.15 69.02,-895.15 69.02,-889.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "81.02,-887.65 99.15,-887.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "54.02,-887.65 35.89,-887.65"
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
              svg.cls    := "node-preview",
              svg.points := "199.52,-889.15 223.52,-889.15 223.52,-895.15 199.52,-895.15 199.52,-889.15"
            ),
            svg.polygon(
              svg.cls    := "node-preview",
              svg.points := "199.52,-880.15 223.52,-880.15 223.52,-886.15 199.52,-886.15 199.52,-880.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "223.52,-887.65 240.64,-887.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "182.41,-887.65 199.52,-887.65"
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
              svg.cls    := "node-preview",
              svg.points := "384.09,-899.65 326.96,-899.65 326.96,-875.65 384.09,-875.65 384.09,-899.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "329.96,-889.15 332.96,-886.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "329.96,-886.15 332.96,-889.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "329.96,-878.65 381.09,-878.65"
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
              svg.cls    := "node-preview",
              svg.points := "505.52,-893.65 505.52,-881.65 493.52,-881.65 493.52,-893.65 505.52,-893.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "508.52,-896.65 508.52,-878.65 490.52,-878.65 490.52,-896.65 508.52,-896.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "508.52,-887.65 526.52,-887.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "472.52,-887.65 490.52,-887.65"
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
              svg.cls    := "node-preview",
              svg.points := "646.52,-893.65 646.52,-895.15 645.02,-896.65 646.52,-898.15 646.52,-899.65 645.02,-899.65 643.52,-898.15 642.02,-899.65 640.52,-899.65 640.52,-898.15 642.02,-896.65 640.52,-895.15 640.52,-893.65 642.02,-893.65 643.52,-895.15 645.02,-893.65 646.52,-893.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "643.52,-887.65 643.52,-889.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "643.52,-890.65 643.52,-892.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "616.52,-887.65 670.52,-887.65"
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
              svg.cls    := "node-preview",
              svg.points := "789.02,-893.65 790.52,-895.15 790.52,-898.15 789.02,-899.65 786.02,-899.65 784.52,-898.15 784.52,-895.15 786.02,-893.65 789.02,-893.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "787.52,-887.65 787.52,-889.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "787.52,-890.65 787.52,-892.15"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "760.52,-887.65 814.52,-887.65"
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
              svg.cls    := "node-preview",
              svg.points := "70.52,-1001.65 70.52,-1003.15 69.02,-1004.65 70.52,-1006.15 70.52,-1007.65 69.02,-1007.65 67.52,-1006.15 66.02,-1007.65 64.52,-1007.65 64.52,-1006.15 66.02,-1004.65 64.52,-1003.15 64.52,-1001.65 66.02,-1001.65 67.52,-1003.15 69.02,-1001.65 70.52,-1001.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "67.52,-1003.15 67.52,-995.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "32.29,-995.65 102.76,-995.65"
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
              svg.cls    := "node-preview",
              svg.points := "213.02,-1001.65 214.52,-1003.15 214.52,-1006.15 213.02,-1007.65 210.02,-1007.65 208.52,-1006.15 208.52,-1003.15 210.02,-1001.65 213.02,-1001.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "211.52,-1001.65 211.52,-995.65"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "178.79,-995.65 244.26,-995.65"
            )
          )
        )
      )

    case Shape.plain =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "497 -142 24 14",
          svg.g(
            svg.polygon(
              svg.cls    := "node-preview",
              svg.points := "521.38,-142.44 497.38,-142.44 497.38,-128.04 521.38,-128.04 521.38,-142.44",
              svg.style  := "stroke:none;fill:lightgrey"
            ),
            svg.text(
              svg.cls    := "node-preview",
              svg.x          := "509.38",
              svg.y          := "-131",
              svg.fontFamily := "Times,serif",
              svg.fontSize   := "13.00",
              svg.textAnchor := "middle",
              "abc"
            )
          )
        )
      )

    case Shape.plaintext =>
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "0 0 24 14",
          svg.g(
            svg.polygon(
              svg.cls    := "node-preview",
              svg.points := "24,0 0,0 0,15 24,15 24,0",
              svg.style  := "stroke:none;fill:lightgrey"
            ),
            svg.text(
              svg.cls    := "node-preview",
              svg.x          := "12",
              svg.y          := "10",
              svg.fontFamily := "Times,serif",
              svg.fontSize   := "7",
              svg.textAnchor := "middle",
              "abc"
            )
          )
        )
      )

    case Shape.underline => // Similar to box, but with an underline
      Some(() =>
        svg.svg(
          svg.width   := w.toString,
          svg.height  := h.toString,
          svg.viewBox := "338 -586 55 37",
          svg.g(
            svg.polygon(
              svg.cls    := "node-preview",
              svg.points := "392.38,-585.24 338.38,-585.24 338.38,-549.24 392.38,-549.24 392.38,-585.24",
              svg.style  := "stroke:none;fill:lightgrey"
            ),
            svg.polyline(
              svg.cls    := "node-preview",
              svg.points := "338.38,-549.24 392.38,-549.24"
            ),
            svg.text(
              svg.cls    := "node-preview",
              svg.x          := "365.38",
              svg.y          := "-560.64",
              svg.fontFamily := "Times,serif",
              svg.fontSize   := "18.00",
              svg.textAnchor := "middle",
              "abc"
            )
          )
        )
      )
    case record => None
