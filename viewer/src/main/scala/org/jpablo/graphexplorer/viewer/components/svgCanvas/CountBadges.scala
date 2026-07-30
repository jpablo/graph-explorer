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

  // Badges are CONTROLS, not diagram content: like the new-arrow circles they keep a
  // constant size ON SCREEN regardless of zoom (drawn at design size around the
  // origin, then scaled so those units land at a fixed client size). Previously they
  // lived in SVG units and ballooned as the user zoomed in.
  private val designRadius   = 7.0
  private val clientDiameter = 18.0 // px on screen, whatever the zoom

  /** None when the element has no usable CTM — a hidden/pre-layout svg. Writing a
    * scale from the getCtmScale fallback of 1 in that state inflated the badge to
    * diagram-units size until the next pan/zoom event; skipping keeps the last
    * good scale instead.
    */
  private def badgeScale(reference: dom.Element): Option[Double] =
    val ctm = reference.asInstanceOf[dom.svg.Locatable].getScreenCTM()
    Option(ctm)
      .map(m => math.abs(m.a))
      .filter(_ > 0)
      .map(s => clientDiameter / (designRadius * 2) / s)

  private def place(g: dom.Element, cx: Double, cy: Double, reference: dom.Element): Unit =
    g.setAttribute("data-cx", cx.toString)
    g.setAttribute("data-cy", cy.toString)
    val k = badgeScale(reference).getOrElse(1.0)
    g.setAttribute("transform", s"translate($cx, $cy) scale($k)")

  /** Re-apply the screen-constant scale after a pan/zoom change (SvgCanvas calls
    * this from its transform binder — the badges themselves never rebuild).
    */
  def rescale(svg: dom.svg.SVG): Unit =
    svg.querySelectorAllT[dom.Element](s"g.$badgeClass").foreach { g =>
      val parent = g.parentNode.asInstanceOf[dom.Element]
      val cx     = g.getAttribute("data-cx")
      val cy     = g.getAttribute("data-cy")
      if cx != null && cy != null && parent != null then
        badgeScale(parent).foreach(k => g.setAttribute("transform", s"translate($cx, $cy) scale($k)"))
    }

  def decorate(
      svg:               dom.svg.SVG,
      strategy:          SelectableElementStrategy,
      concealed:         Map[NodeId, (Int, Int)],
      onToggleConcealed: (NodeId, Boolean) => Unit, // (node, successorSide)
      collapsed:         Map[NodeId, Int],
      onToggleCollapsed: NodeId => Unit,
      onCollapseGroup:   GroupId => Unit
  ): Unit =
    // Adopted exit ghosts keep their node/cluster classes (styling) — a badge on
    // one would decorate scenery from the previous layout.
    def isGhost(el: dom.Element): Boolean =
      el.closest(s".${LayoutTransition.ghostClass}") != null
    if concealed.nonEmpty || collapsed.nonEmpty then
      svg.querySelectorAllT[dom.Element](strategy.nodeSelector).filterNot(isGhost).foreach { el =>
        val id = strategy.extractNodeId(el)
        lazy val bb = el.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
        def addBadge(cx: Double, cy: Double, label: String, cls: String, tooltip: String, onToggle: () => Unit): Unit =
          val b = badge(label, cls, tooltip, onToggle)
          el.appendChild(b)
          place(b, cx, cy, el)
        concealed.get(id).foreach { (succ, pred) =>
          val cy = bb.y + bb.height / 2.0
          if succ > 0 then
            addBadge(bb.x + bb.width, cy, countLabel(succ), badgeClass, s"$succ hidden successor(s) — click to show", () => onToggleConcealed(id, true))
          if pred > 0 then
            addBadge(bb.x, cy, countLabel(pred), badgeClass, s"$pred hidden predecessor(s) — click to show", () => onToggleConcealed(id, false))
        }
        collapsed.get(id).foreach { members =>
          addBadge(bb.x + bb.width, bb.y, countLabel(members), s"$badgeClass $collapseClass", s"$members member(s) — click to expand", () => onToggleCollapsed(id))
        }
      }
    // Every rendered cluster is a group that CAN collapse — the affordance the
    // collapsed box's count badge promises in reverse. Same corner, so the
    // control stays put when the group folds.
    svg.querySelectorAllT[dom.Element](strategy.clusterSelector).filterNot(isGhost).foreach { el =>
      val gid = strategy.extractGroupId(el)
      val bb  = el.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
      val b   = badge("−", s"$badgeClass $foldClass", "Collapse group into one box", () => onCollapseGroup(gid))
      el.appendChild(b)
      place(b, bb.x + bb.width, bb.y, el)
    }

  private def countLabel(count: Int): String =
    if count > 99 then "99+" else count.toString

  // Geometry is origin-centered: the group's transform (see [[place]]) carries both
  // the position and the screen-constant scale.
  private def badge(
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
    c.setAttribute("cx", "0")
    c.setAttribute("cy", "0")
    c.setAttribute("r", designRadius.toString)
    g.appendChild(c)

    val t = dom.document.createElementNS(SvgNS, "text")
    t.setAttribute("x", "0")
    t.setAttribute("y", "0")
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
