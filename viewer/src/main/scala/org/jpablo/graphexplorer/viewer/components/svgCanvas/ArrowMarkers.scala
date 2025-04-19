package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*

def arrowHeadMarker(markerId: String) =
  svg.marker(
    svg.idAttr       := markerId,
    svg.viewBox      := "0 0 10 10",
    svg.refX         := "9",
    svg.refY         := "5",
    svg.markerWidth  := "4", // Smaller size
    svg.markerHeight := "4", // Smaller size
    svg.orient       := "auto-start-reverse",
    // Vee style path with concave base
    svg.path(svg.d := "M 0 0 L 10 5 L 0 10 L 2 5 z")
  )

def arrowTailMarker(markerId: String) =
  svg.marker(
    svg.idAttr       := markerId,
    svg.viewBox      := "0 0 10 10",
    svg.refX         := "5", // Center the reference point
    svg.refY         := "5", // Center the reference point
    svg.markerWidth  := "4", // Same size as arrowhead
    svg.markerHeight := "4", // Same size as arrowhead
    svg.orient       := "auto",
    // Small disk (circle)
    svg.circle(svg.cx := "5", svg.cy := "5", svg.r := "3")
  )
