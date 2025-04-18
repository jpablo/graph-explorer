package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*


def arrowHeadMarker =
  svg.defs(
    svg.marker(
      svg.idAttr := "arrowhead",
      svg.viewBox := "0 0 10 10",
      svg.refX := "9",
      svg.refY := "5",
      svg.markerWidth := "4", // Smaller size
      svg.markerHeight := "4", // Smaller size
      svg.orient := "auto-start-reverse",
      // Vee style path with concave base
      svg.path(svg.d := "M 0 0 L 10 5 L 0 10 L 2 5 z")
    )
  )
