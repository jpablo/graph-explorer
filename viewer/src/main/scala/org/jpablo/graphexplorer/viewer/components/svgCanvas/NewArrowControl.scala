package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.components.selection.{NodeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Rankdir
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Rankdir.*

def NewArrowControl(
    elem:       SelectableElement,
    getRankdir: () => Rankdir,
    svgMods:    SvgMods*
): Option[ReactiveSvgElement[dom.svg.G]] =
  val radius  = 8
  val centerX = 8
  val centerY = 8

  elem match
    case NodeElement(ref) =>
      val bbox = ref.getBBox()
      // Original width and height of the icon
      val w = radius * 2
      val h = radius * 2

      val scale = SvgUtils.calculateSimpleScale(ref, w.toDouble, clientSize = 20)

      // Get the rankdir value from graph attributes
      val rankdir = getRankdir()

      // Calculate position and rotation based on rankdir
      val (trX, trY, rotation) = rankdir match
        case TB => // Top to Bottom - show below, no rotation needed (default)
          (bbox.x + bbox.width / 2 - (w * scale) / 2, bbox.y + bbox.height + (h * scale) / 4 + 1, 0)
        case LR => // Left to Right - show to the right, rotate 270 degrees
          (bbox.x + bbox.width + (w * scale) / 4 + 1, bbox.y + bbox.height / 2 - (h * scale) / 2, 270)
        case BT => // Bottom to Top - show above, rotate 180 degrees
          (bbox.x + bbox.width / 2 - (w * scale) / 2, bbox.y - (h * scale) - (h * scale) / 4 - 1, 180)
        case RL => // Right to Left - show to the left, rotate 90 degrees
          (bbox.x - (w * scale) - (w * scale) / 4 - 1, bbox.y + bbox.height / 2 - (h * scale) / 2, 90)

      val arrowGroup =
        svg.g(
          svg.path(
            svg.d := "M8.5 4.5a.5.5 0 0 0-1 0v5.793L5.354 8.146a.5.5 0 1 0-.708.708l3 3a.5.5 0 0 0 .708 0l3-3a.5.5 0 0 0-.708-.708L8.5 10.293z"
          )
        )

      Some(
        svg.g(
          svg.cls           := s"new-arrow-control",
          svg.pointerEvents := "all",
          svg.circle(svg.r := radius.toString, svg.cx := centerX.toString, svg.cy := centerY.toString),
          arrowGroup,
          svg.transform := s"translate($trX, $trY) scale($scale)",
          arrowGroup.amend(svg.transform := s"rotate($rotation, $centerX, $centerY)"),
          svgMods
        )
      )
    case _ => None
