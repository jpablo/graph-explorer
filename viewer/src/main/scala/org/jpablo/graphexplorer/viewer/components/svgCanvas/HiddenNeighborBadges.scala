package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.viewer.components.selection.SelectableElementStrategy
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.scalajs.dom

import scala.scalajs.js

/** The tree-view triangle, for graphs: a node with CONCEALED direct neighbors
  * wears a small count badge on the corresponding side — successors on the
  * right edge, predecessors on the left — so "navigate and expand" is not
  * blind. Clicking a badge toggles that side (select + expand/contract),
  * exactly like clicking a tree triangle.
  *
  * Decoration only: badges are appended INSIDE each node's `<g>` after layout,
  * so they ride pan/zoom and never perturb the diagram's geometry. Must run
  * on a MOUNTED svg — `getBBox` is meaningless on a detached element.
  */
object HiddenNeighborBadges:

  val badgeClass = "gx-expand-badge"

  private val SvgNS = "http://www.w3.org/2000/svg"

  def decorate(
      svg:      dom.svg.SVG,
      strategy: SelectableElementStrategy,
      counts:   Map[NodeId, (Int, Int)],
      onToggle: (NodeId, Boolean) => Unit // (node, successorSide)
  ): Unit =
    if counts.nonEmpty then
      svg.querySelectorAllT[dom.Element](strategy.nodeSelector).foreach { el =>
        val id = strategy.extractNodeId(el)
        counts.get(id).foreach { (succ, pred) =>
          val bb = el.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
          val cy = bb.y + bb.height / 2.0
          if succ > 0 then el.appendChild(badge(bb.x + bb.width, cy, succ, id, successorSide = true, onToggle))
          if pred > 0 then el.appendChild(badge(bb.x, cy, pred, id, successorSide = false, onToggle))
        }
      }

  private def badge(
      cx:            Double,
      cy:            Double,
      count:         Int,
      id:            NodeId,
      successorSide: Boolean,
      onToggle:      (NodeId, Boolean) => Unit
  ): dom.Element =
    val g = dom.document.createElementNS(SvgNS, "g")
    g.setAttribute("class", badgeClass)

    val c = dom.document.createElementNS(SvgNS, "circle")
    c.setAttribute("cx", cx.toString)
    c.setAttribute("cy", cy.toString)
    c.setAttribute("r", "7")
    g.appendChild(c)

    val t = dom.document.createElementNS(SvgNS, "text")
    t.setAttribute("x", cx.toString)
    t.setAttribute("y", cy.toString)
    t.setAttribute("dy", "0.34em")
    t.textContent = if count > 99 then "99+" else count.toString
    g.appendChild(t)

    // The badge is its own control: stop the canvas machinery (drag start,
    // click-resolution) from treating the press as a node interaction.
    g.addEventListener("pointerdown", (ev: dom.Event) => ev.stopPropagation())
    g.addEventListener("mousedown", (ev: dom.Event) => ev.stopPropagation())
    g.addEventListener(
      "click",
      { (ev: dom.Event) =>
        ev.stopPropagation()
        onToggle(id, successorSide)
      }
    )
    g
