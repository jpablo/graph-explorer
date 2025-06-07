package org.jpablo.graphexplorer.viewer.components.attributes.previews

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.CornerStyle

def CornerPreview(corner: CornerStyle): Option[() => SvgElement] =
  corner match
    case CornerStyle.rounded   => Some(() => roundedIcon())
    case CornerStyle.diagonals => Some(() => diagonalsIcon())
    case CornerStyle.normal    => Some(() => normalIcon())

def roundedIcon(width: String = "17px", height: String = "17px"): SvgElement =
  svg.svg(
    svg.width    := width,
    svg.height   := height,
    svg.viewBox  := "70 -38 26 26",
    svg.xmlns    := "http://www.w3.org/2000/svg",
    svg.path(
      svg.d     := "M94,-36 L84,-36 C78,-36 72,-30 72,-24 L72,-14",
      svg.style := "fill:none;stroke:black;stroke-width:1px; vector-effect: non-scaling-stroke;"
    )
  )

def diagonalsIcon(width: String = "17px", height: String = "17px"): SvgElement =
  svg.svg(
    svg.width    := width,
    svg.height   := height,
    svg.viewBox  := "142 -38 26 26",
    svg.xmlns    := "http://www.w3.org/2000/svg",
    svg.path(
      svg.d     := "M166,-36 L144,-36 L144,-14 M156,-36 L144,-24",
      svg.style := "fill:none;stroke:black;stroke-width:1px; vector-effect: non-scaling-stroke;"
    )
  )

def normalIcon(width: String = "17px", height: String = "17px"): SvgElement =
  svg.svg(
    svg.width    := width,
    svg.height   := height,
    svg.viewBox  := "0 0 17 17",
    svg.xmlns    := "http://www.w3.org/2000/svg",
    svg.path(
      svg.d     := "M 15 2 L 2 2 L 2 15",
      svg.style := "fill:none;stroke:black;stroke-width:1px;stroke-linejoin:miter; vector-effect: non-scaling-stroke;"
    )
  )
