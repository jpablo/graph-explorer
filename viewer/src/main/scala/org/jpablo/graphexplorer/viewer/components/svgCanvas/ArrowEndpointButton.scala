package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.selection.EdgeElement

/**
 * Creates a small disk placed near the endpoint of an edge.
 * Diameter: 8px, Border: 1px
 *
 * @param elem The EdgeElement the disk is associated with.
 * @param start Whether this disk is for the start or end point (currently unused).
 * @return A reactive SVG group element containing the disk.
 */
def ArrowEndpointButton(
    elem:  EdgeElement,
    start: Boolean // TODO: Use this parameter to position correctly at start/end
): ReactiveSvgElement[dom.svg.G] =
  // Define disk properties
  val diskDiameter = 8
  val diskRadius   = diskDiameter / 2
  val centerX      = diskRadius
  val centerY      = diskRadius

  val ref  = elem.ref
  val bbox = ref.getBBox()
  // Original width and height of the disk
  val w = diskDiameter
  val h = diskDiameter

  // Calculate position based on the bounding box of the edge element
  // NOTE: This positioning might need adjustment based on actual edge coordinates
  // It currently places the disk relative to the edge's overall bounding box.
  val trX = bbox.x + bbox.width / 2 - diskRadius
  val trY = bbox.y + bbox.height + 3 // Approximation, adjust as needed

  svg.g(
    svg.cls           := s"edge-endpoint-disk",
    svg.pointerEvents := "all", // Keep interactive if needed
    svg.circle(
      svg.r           := diskRadius.toString,
      svg.cx          := centerX.toString,
      svg.cy          := centerY.toString,
      svg.fill        := "white",
      svg.stroke      := "black",
      svg.strokeWidth := "1"
    ),
    svg.transform := s"translate($trX, $trY)"
    // Removed scaling and rotation
  )
