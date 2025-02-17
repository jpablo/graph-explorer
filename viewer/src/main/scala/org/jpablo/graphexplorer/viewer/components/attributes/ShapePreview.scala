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
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val cx = x + w/2
              val cy = y + h/2
              s"M${cx - w/4},${cy + h/3} " +
                s"C${x},${cy + h/3} ${x},${cy - h/3} ${cx},${y} " +
                s"C${x + w},${cy - h/3} ${x + w},${cy + h/3} ${cx + w/4},${cy + h/3} " +
                s"C${cx + w/4},${y + h} ${cx - w/4},${y + h} ${cx - w/4},${cy + h/3}"
            },
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
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val ellipseHeight = Math.min(6, h / 4)
              s"M$x,${y + ellipseHeight} " +
                s"L$x,${y + h - ellipseHeight} " +
                s"A${w/2},$ellipseHeight 0 0 0 ${x + w},${y + h - ellipseHeight} " +
                s"L${x + w},${y + ellipseHeight} " +
                s"A${w/2},$ellipseHeight 0 0 0 $x,${y + ellipseHeight} " +
                s"M$x,${y + ellipseHeight} " +
                s"A${w/2},$ellipseHeight 0 0 1 ${x + w},${y + ellipseHeight}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.note =>
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
              val fold = Math.min(w, h) * 0.2
              s"M$x,$y " +
                s"L${x + w - fold},$y " +
                s"L${x + w},${y + fold} " +
                s"L${x + w},${y + h} " +
                s"L$x,${y + h} Z " +
                s"M${x + w - fold},$y " +
                s"L${x + w - fold},${y + fold} " +
                s"L${x + w},${y + fold}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.tab =>
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
              val tab = h * 0.2
              s"M$x,${y + tab} " +
                s"L${x + tab},${y} " +
                s"L${x + w},${y} " +
                s"L${x + w},${y + h} " +
                s"L$x,${y + h} Z"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.folder =>
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
              val tab = w * 0.3
              val fold = h * 0.2
              s"M$x,${y + fold} " +
                s"L${x + tab},${y} " +
                s"L${x + w},${y} " +
                s"L${x + w},${y + h} " +
                s"L$x,${y + h} Z"
            },
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
          svg.path(
            svg.d := {
              val x = 2
              val y = 2
              val w = width - 4
              val h = height - 4
              val depth = Math.min(w, h) * 0.2
              s"M$x,${y + depth} " +
                s"L${x + depth},${y} " +
                s"L${x + w},${y} " +
                s"L${x + w},${y + h - depth} " +
                s"L${x + w - depth},${y + h} " +
                s"L$x,${y + h} Z " +
                s"M${x + w},${y} " +
                s"L${x + w - depth},${y + depth} " +
                s"L${x + w - depth},${y + h} " +
                s"M${x + w - depth},${y + depth} " +
                s"L$x,${y + depth}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
          )
        )
      )

    case Shape.component =>
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
              val tab = Math.min(w, h) * 0.2
              s"M${x + tab},$y " +
                s"L${x + w},$y " +
                s"L${x + w},${y + h} " +
                s"L${x + tab},${y + h} " +
                s"L$x,${y + h - tab} " +
                s"L$x,${y + tab} Z " +
                s"M${x + tab},$y " +
                s"L${x + tab},${y + tab} " +
                s"L$x,${y + tab} " +
                s"M${x + tab},${y + h} " +
                s"L${x + tab},${y + h - tab} " +
                s"L$x,${y + h - tab}"
            },
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
          svg.g(
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
            ),
            svg.line(
              svg.x1 := (width / 2 - (width / 2 - 2) * 0.3).toString,
              svg.y1 := (height * 0.3).toString,
              svg.x2 := (width / 2 + (width / 2 - 2) * 0.3).toString,
              svg.y2 := (height * 0.3).toString,
              svg.stroke := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.line(
              svg.x1 := (width / 2 - (width / 2 - 2) * 0.3).toString,
              svg.y1 := (height * 0.7).toString,
              svg.x2 := (width / 2 + (width / 2 - 2) * 0.3).toString,
              svg.y2 := (height * 0.7).toString,
              svg.stroke := "currentColor",
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
          svg.g(
            svg.rect(
              svg.x := "2",
              svg.y := "2",
              svg.width := (width - 4).toString,
              svg.height := (height - 4).toString,
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.line(
              svg.x1 := (width * 0.2).toString,
              svg.y1 := (height * 0.3).toString,
              svg.x2 := (width * 0.8).toString,
              svg.y2 := (height * 0.3).toString,
              svg.stroke := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.line(
              svg.x1 := (width * 0.2).toString,
              svg.y1 := (height * 0.7).toString,
              svg.x2 := (width * 0.8).toString,
              svg.y2 := (height * 0.7).toString,
              svg.stroke := "currentColor",
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
          svg.g(
            svg.circle(
              svg.cx := (width / 2).toString,
              svg.cy := (height / 2).toString,
              svg.r := (Math.min(width, height) / 2 - 2).toString,
              svg.stroke := "currentColor",
              svg.fill := "none",
              svg.strokeWidth := "2"
            ),
            svg.line(
              svg.x1 := (width * 0.2).toString,
              svg.y1 := (height * 0.3).toString,
              svg.x2 := (width * 0.8).toString,
              svg.y2 := (height * 0.3).toString,
              svg.stroke := "currentColor",
              svg.strokeWidth := "2"
            ),
            svg.line(
              svg.x1 := (width * 0.2).toString,
              svg.y1 := (height * 0.7).toString,
              svg.x2 := (width * 0.8).toString,
              svg.y2 := (height * 0.7).toString,
              svg.stroke := "currentColor",
              svg.strokeWidth := "2"
            )
          )
        )
      )

    case Shape.plaintext | Shape.plain | Shape.none =>
      None

    case Shape.promoter | Shape.lpromoter | Shape.rpromoter =>
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
              shape match
                case Shape.lpromoter =>
                  s"M${x + w},${y + h/2} " +
                    s"L${x + w - arrowSize},${y + h/2} " +
                    s"L${x + w - arrowSize},${y} " +
                    s"L$x,${y + h/2} " +
                    s"L${x + w - arrowSize},${y + h} " +
                    s"L${x + w - arrowSize},${y + h/2}"
                case Shape.rpromoter =>
                  s"M$x,${y + h/2} " +
                    s"L${x + arrowSize},${y + h/2} " +
                    s"L${x + arrowSize},${y} " +
                    s"L${x + w},${y + h/2} " +
                    s"L${x + arrowSize},${y + h} " +
                    s"L${x + arrowSize},${y + h/2}"
                case _ =>
                  s"M${x + w/2},${y + h/2} " +
                    s"L${x + w/2 - arrowSize},${y + h/2} " +
                    s"L${x + w/2 - arrowSize},${y} " +
                    s"L$x,${y + h/2} " +
                    s"L${x + w/2 - arrowSize},${y + h} " +
                    s"L${x + w/2 - arrowSize},${y + h/2}"
            },
            svg.stroke := "currentColor",
            svg.fill := "none",
            svg.strokeWidth := "2"
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

    case _ => None 