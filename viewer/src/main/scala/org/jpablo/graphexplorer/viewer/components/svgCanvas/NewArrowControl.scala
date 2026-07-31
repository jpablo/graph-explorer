package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.components.selection.NodeElement
import org.jpablo.graphexplorer.viewer.domUtils.{DOMPoint, SvgUtils}
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
    screenCtm:  Option[dom.SVGMatrix] = None,
    /** Which of the node's SIDE edges a count badge already straddles.
      * CountBadges puts the successor count on the right and the predecessor
      * count on the left whatever the rankdir, so under LR/RL a control lands
      * on exactly the edge a badge occupies — it takes the lane outside the
      * badge rather than sitting on top of it.
      */
    occupiedSides: (left: Boolean, right: Boolean) = (left = false, right = false),
    svgMods:       SvgMods*
): ReactiveSvgElement[dom.svg.G] =
  val radius  = 8
  val centerX = 8
  val centerY = 8

  val fallbackBBox = elem.ref.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
  val elemCtm      = Option(elem.ref.asInstanceOf[js.Dynamic].getScreenCTM().asInstanceOf[dom.SVGMatrix])
  val rootCtm      = screenCtm.orElse(elemCtm)
  val (bboxX, bboxY, bboxWidth, bboxHeight) =
    (rootCtm, elemCtm) match
      case (Some(rootMatrix), Some(elemMatrix)) =>
        val inv = rootMatrix.inverse()
        def toParent(x: Double, y: Double): DOMPoint =
          new DOMPoint(x, y).matrixTransform(elemMatrix).matrixTransform(inv)

        val p1   = toParent(fallbackBBox.x, fallbackBBox.y)
        val p2   = toParent(fallbackBBox.x + fallbackBBox.width, fallbackBBox.y)
        val p3   = toParent(fallbackBBox.x, fallbackBBox.y + fallbackBBox.height)
        val p4   = toParent(fallbackBBox.x + fallbackBBox.width, fallbackBBox.y + fallbackBBox.height)
        val minX = List(p1.x, p2.x, p3.x, p4.x).min
        val minY = List(p1.y, p2.y, p3.y, p4.y).min
        val maxX = List(p1.x, p2.x, p3.x, p4.x).max
        val maxY = List(p1.y, p2.y, p3.y, p4.y).max
        (minX, minY, maxX - minX, maxY - minY)
      case _ =>
        (fallbackBBox.x, fallbackBBox.y, fallbackBBox.width, fallbackBBox.height)
  // Original width and height of the icon
  val w = radius * 2
  val h = radius * 2

  // Determine clientSize based on viewport width
  val currentClientSize = clientSize match
    case ClientSize.Small => 32.0
    case ClientSize.Normal => 16.0

  val scale = SvgUtils.calculateSimpleScale(elem.ref.asInstanceOf[dom.svg.Locatable], w.toDouble, clientSize = currentClientSize)

  // User units per CLIENT pixel. The control holds a constant SCREEN size, so
  // the gaps around it must be screen-constant too: they are all stated in px
  // below and converted here. (The clearance used to carry a bare `+ 1` in USER
  // units — a gap that shrank as the canvas zoomed out, until the control
  // climbed onto whatever sat on the node's edge.)
  val userPerPx = SvgUtils.calculateSimpleScale(elem.ref.asInstanceOf[dom.svg.Locatable], svgSize = 1, clientSize = 1)

  // Air between the node's edge and the control, proportional to the control
  // itself (a bigger touch target on a small screen wants more room).
  val edgeGapPx = currentClientSize / 4.0

  // The OUTER LANE: a count badge is centred ON the edge it marks, reaching
  // half its diameter past it, so a control sharing that edge starts beyond
  // the badge's rim — the two read as a sequence ([node][4]→( → )) instead of
  // two overlapping circles.
  val badgeLanePx = CountBadges.clientDiameter / 2.0 + 3.0

  // Get the rankdir value from graph attributes
  val rankdir = getRankdir()

  // Calculate scaled dimensions and node center
  val scaledW     = w * scale
  val scaledH     = h * scale
  val nodeCenterX = bboxX + bboxWidth / 2
  val nodeCenterY = bboxY + bboxHeight / 2

  def clearance(occupied: Boolean): Double =
    (edgeGapPx + (if occupied then badgeLanePx else 0.0)) * userPerPx

  // Pre-calculate potential positions. Only the sides take a lane: badges never
  // sit on the top or bottom edge, so TB/BT keep today's spacing exactly.
  val posAbove = (nodeCenterX - scaledW / 2, bboxY - scaledH - clearance(false))
  val posBelow = (nodeCenterX - scaledW / 2, bboxY + bboxHeight + clearance(false))
  val posLeft  = (bboxX - scaledW - clearance(occupiedSides.left), nodeCenterY - scaledH / 2)
  val posRight = (bboxX + bboxWidth + clearance(occupiedSides.right), nodeCenterY - scaledH / 2)

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
