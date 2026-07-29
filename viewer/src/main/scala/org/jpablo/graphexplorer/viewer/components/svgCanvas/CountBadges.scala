package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.viewer.components.selection.SelectableElementStrategy
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.jpablo.graphexplorer.viewer.models.{GroupId, NodeId}
import org.scalajs.dom

import scala.scalajs.js

/** The tree-view triangle, for graphs: a node with CONCEALED direct neighbors
  * wears a small count badge on the corresponding side — successors on the
  * right edge, predecessors on the left — so "navigate and expand" is not
  * blind. A COLLAPSED group's box wears its member count on the top-right
  * corner; an EXPANDED group wears a "−" in the same spot, so the two states
  * are one control in two phases. Clicking any badge toggles what it marks
  * (select + expand or contract), exactly like clicking a tree triangle.
  *
  * Decoration only: badges are appended INSIDE each node's `<g>` after layout,
  * so they ride pan/zoom and never perturb the diagram's geometry. Must run
  * on a MOUNTED svg — `getBBox` is meaningless on a detached element.
  */
object CountBadges:

  val badgeClass    = "gx-expand-badge"
  val collapseClass = "gx-collapse-badge"
  val foldClass     = "gx-fold-badge"

  private val SvgNS = "http://www.w3.org/2000/svg"

  def decorate(
      svg:               dom.svg.SVG,
      strategy:          SelectableElementStrategy,
      concealed:         Map[NodeId, (Int, Int)],
      onToggleConcealed: (NodeId, Boolean) => Unit, // (node, successorSide)
      collapsed:         Map[NodeId, Int],
      onToggleCollapsed: NodeId => Unit,
      onCollapseGroup:   GroupId => Unit
  ): Unit =
    if concealed.nonEmpty || collapsed.nonEmpty then
      svg.querySelectorAllT[dom.Element](strategy.nodeSelector).foreach { el =>
        val id = strategy.extractNodeId(el)
        lazy val bb = el.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
        concealed.get(id).foreach { (succ, pred) =>
          val cy = bb.y + bb.height / 2.0
          if succ > 0 then
            el.appendChild(
              badge(bb.x + bb.width, cy, countLabel(succ), badgeClass, s"$succ hidden successor(s) — click to show", () => onToggleConcealed(id, true))
            )
          if pred > 0 then
            el.appendChild(
              badge(bb.x, cy, countLabel(pred), badgeClass, s"$pred hidden predecessor(s) — click to show", () => onToggleConcealed(id, false))
            )
        }
        collapsed.get(id).foreach { members =>
          el.appendChild(
            badge(bb.x + bb.width, bb.y, countLabel(members), s"$badgeClass $collapseClass", s"$members member(s) — click to expand", () => onToggleCollapsed(id))
          )
        }
      }
    // Every rendered cluster is a group that CAN collapse — the affordance the
    // collapsed box's count badge promises in reverse. Same corner, so the
    // control stays put when the group folds.
    svg.querySelectorAllT[dom.Element](strategy.clusterSelector).foreach { el =>
      val gid = strategy.extractGroupId(el)
      val bb  = el.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
      el.appendChild(
        badge(bb.x + bb.width, bb.y, "−", s"$badgeClass $foldClass", "Collapse group into one box", () => onCollapseGroup(gid))
      )
    }

  private def countLabel(count: Int): String =
    if count > 99 then "99+" else count.toString

  private def badge(
      cx:       Double,
      cy:       Double,
      label:    String,
      cls:      String,
      tooltip:  String,
      onToggle: () => Unit
  ): dom.Element =
    val g = dom.document.createElementNS(SvgNS, "g")
    g.setAttribute("class", cls)

    val title = dom.document.createElementNS(SvgNS, "title")
    title.textContent = tooltip
    g.appendChild(title)

    val c = dom.document.createElementNS(SvgNS, "circle")
    c.setAttribute("cx", cx.toString)
    c.setAttribute("cy", cy.toString)
    c.setAttribute("r", "7")
    g.appendChild(c)

    val t = dom.document.createElementNS(SvgNS, "text")
    t.setAttribute("x", cx.toString)
    t.setAttribute("y", cy.toString)
    t.setAttribute("dy", "0.34em")
    t.textContent = label
    g.appendChild(t)

    // The badge is its own control: stop the canvas machinery (drag start,
    // click-resolution) from treating the press as a node interaction.
    g.addEventListener("pointerdown", (ev: dom.Event) => ev.stopPropagation())
    g.addEventListener("mousedown", (ev: dom.Event) => ev.stopPropagation())
    g.addEventListener(
      "click",
      { (ev: dom.Event) =>
        ev.stopPropagation()
        onToggle()
      }
    )
    g
