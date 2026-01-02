package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.components.selection.NodeElement
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Rankdir
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Rankdir.*
import org.jpablo.graphexplorer.viewer.models.ArrowDirection
import org.jpablo.graphexplorer.viewer.models.ClientSize
import scala.scalajs.js

def NewArrowControl(
    elem:       NodeElement,
    getRankdir: () => Rankdir,
    direction:  ArrowDirection,
    clientSize: ClientSize,
    svgMods:    SvgMods*
): ReactiveSvgElement[dom.svg.G] =
  val radius  = 8
  val centerX = 8
  val centerY = 8

  val bbox = elem.ref.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
  // Original width and height of the icon
  val w = radius * 2
  val h = radius * 2

  // Determine clientSize based on viewport width
  val currentClientSize = clientSize match
    case ClientSize.Small => 32.0
    case ClientSize.Normal => 16.0

  val scale = SvgUtils.calculateSimpleScale(elem.ref.asInstanceOf[dom.svg.Locatable], w.toDouble, clientSize = currentClientSize)

  // Get the rankdir value from graph attributes
  val rankdir = getRankdir()

  // Calculate scaled dimensions and node center
  val scaledW     = w * scale
  val scaledH     = h * scale
  val nodeCenterX = bbox.x + bbox.width / 2
  val nodeCenterY = bbox.y + bbox.height / 2

  // Pre-calculate potential positions
  val posAbove = (nodeCenterX - scaledW / 2, bbox.y - scaledH - scaledH / 4 - 1)
  val posBelow = (nodeCenterX - scaledW / 2, bbox.y + bbox.height + scaledH / 4 + 1)
  val posLeft  = (bbox.x - scaledW - scaledW / 4 - 1, nodeCenterY - scaledH / 2)
  val posRight = (bbox.x + bbox.width + scaledW / 4 + 1, nodeCenterY - scaledH / 2)

  // Determine position based on rankdir and direction
  val (trX, trY) = (rankdir, direction) match
    case (TB, ArrowDirection.forward)  => posBelow
    case (TB, ArrowDirection.backward) => posAbove
    case (LR, ArrowDirection.forward)  => posRight
    case (LR, ArrowDirection.backward) => posLeft
    case (BT, ArrowDirection.forward)  => posAbove
    case (BT, ArrowDirection.backward) => posBelow
    case (RL, ArrowDirection.forward)  => posLeft
    case (RL, ArrowDirection.backward) => posRight

  // Determine rotation based only on rankdir (direction of graph flow)
  // The base arrow icon points downwards (rotation 0)
  val rotation = rankdir match
    case TB => 0   // Flow is Down
    case LR => 270 // Flow is Right
    case BT => 180 // Flow is Up
    case RL => 90  // Flow is Left

  val arrowGroup =
    svg.g(
      // Apply rotation to the arrow icon itself
      svg.transform := s"rotate($rotation, $centerX, $centerY)",
      svg.path(
        svg.d := "M8.5 4.5a.5.5 0 0 0-1 0v5.793L5.354 8.146a.5.5 0 1 0-.708.708l3 3a.5.5 0 0 0 .708 0l3-3a.5.5 0 0 0-.708-.708L8.5 10.293z"
      )
    )

  svg.g(
    svg.cls           := s"new-arrow-control",
    svg.pointerEvents := "all",
    svg.circle(svg.r := radius.toString, svg.cx := centerX.toString, svg.cy := centerY.toString),
    arrowGroup,
    svg.transform := s"translate($trX, $trY) scale($scale)",
    svgMods
  )
