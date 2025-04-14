package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.components.selection.EdgeElement
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils

object SVGSimplePathParser:
  // Regular expression to match the 'M' command followed by coordinates
  private val MoveCommandRegex = """M\s*([+-]?\d*\.?\d+)\s*,\s*([+-]?\d*\.?\d+).*""".r

  /** Parses an SVG path string and extracts the coordinates after the 'M' command.
    *
    * @param pathData
    *   The SVG path string
    * @return
    *   An Option containing the (x,y) coordinates if found, None otherwise
    */
  def parseCoordinatesAfterM(pathData: String): Option[(Double, Double)] =
    pathData match
      case MoveCommandRegex(x, y) => Some((x.toDouble, y.toDouble))
      case _                      => None

/** Creates a small disk placed near the endpoint of an edge. Diameter: 8px, Border: 1px
  *
  * @param elem
  *   The EdgeElement the disk is associated with.
  * @param start
  *   Whether this disk is for the start or end point (currently unused).
  * @return
  *   A reactive SVG group element containing the disk.
  */
def ArrowEndpointButton(
    elem:    EdgeElement,
    start:   Boolean,
    svgMods: SvgMods*
): ReactiveSvgElement[dom.svg.G] =
  // Define disk properties
  val radius  = 8
  val centerX = 0
  val centerY = 0
  val w       = radius * 2
  val h       = radius * 2

  val svgPath = elem.ref.querySelector("path").asInstanceOf[dom.svg.Path]
  dom.console.log(svgPath)
  val startPoint = SVGSimplePathParser.parseCoordinatesAfterM(svgPath.getAttribute("d"))
  val scale      = SvgUtils.calculateSimpleScale(elem.ref, w.toDouble, clientSize = 15)

  val bbox = elem.ref.getBBox()
  val trX  = startPoint.map(_._1).getOrElse(bbox.x)
  val trY  = startPoint.map(_._2).getOrElse(bbox.y)

  svg.g(
    svg.cls           := s"edge-endpoint-disk",
    svg.pointerEvents := "all", // Keep interactive if needed
    svg.circle(
      svg.r           := radius.toString,
      svg.cx          := centerX.toString,
      svg.cy          := centerY.toString,
      svg.fill        := "white",
      svg.stroke      := "black",
      svg.strokeWidth := "1"
    ),
    svg.transform := s"translate($trX, $trY) scale($scale)",
    svgMods
  )
