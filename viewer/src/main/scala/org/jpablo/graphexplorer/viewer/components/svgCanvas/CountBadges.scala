package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.domUtils.{DOMPoint, querySelectorAllT}
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
  * Decoration only: badges are drawn into one overlay LAYER appended last
  * inside the main group, so they ride pan/zoom and never perturb the
  * diagram's geometry. Last means ON TOP: a badge parked in its own node's
  * `<g>` painted under every edge (graphviz emits edges after nodes, and svg
  * paint order is document order), so a control the user is meant to click sat
  * beneath the splines crossing it. The new-arrow circles have always lived in
  * this layer — the badges just joined them.
  *
  * Positions come from each element's `getBBox`, mapped THROUGH the element's
  * own transform into the layer's frame (graphviz node groups carry none, so
  * the two frames coincide; Mermaid's translate their nodes). Must run on a
  * MOUNTED svg — `getBBox` is meaningless on a detached element.
  */
object CountBadges:

  val badgeClass    = "gx-expand-badge"
  val collapseClass = "gx-collapse-badge"
  val foldClass     = "gx-fold-badge"

  /** On a fold badge whose group is the current subject — the stylesheet shows
    * only those. It used to be read off the DOM (`.selected > .gx-fold-badge`),
    * which the move out of the cluster group took away. */
  val activeClass = "gx-badge-active"

  private val layerClass = "gx-badge-layer"
  private val gidAttr    = "data-gid"

  private val SvgNS = "http://www.w3.org/2000/svg"

  // Badges are CONTROLS, not diagram content: like the new-arrow circles they keep a
  // constant size ON SCREEN regardless of zoom (drawn at design size around the
  // origin, then scaled so those units land at a fixed client size). Previously they
  // lived in SVG units and ballooned as the user zoomed in.
  private val designRadius = 7.0

  /** The badge's size ON SCREEN, whatever the zoom. Public because a badge
    * OCCUPIES the edge it marks — it straddles the border, reaching half of
    * this beyond it — and NewArrowControl has to step around one. */
  val clientDiameter = 18.0

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

  /** The overlay layer, created on first use as the LAST child of the main
    * group — its position in document order is the whole point. */
  private def layerOf(mainGroup: dom.Element): dom.Element =
    Option(mainGroup.querySelector(s"g.$layerClass")).getOrElse:
      val g = dom.document.createElementNS(SvgNS, "g")
      g.setAttribute("class", layerClass)
      mainGroup.appendChild(g)
      g

  /** A point in `el`'s user space, restated in the layer's. Identity for
    * graphviz (nodes carry no transform); a translation for Mermaid, whose
    * node groups do — without it every Mermaid badge would pile up at the
    * diagram's origin. */
  private def toLayer(el: dom.Element, layer: dom.Element, x: Double, y: Double): (Double, Double) =
    val elemCtm  = Option(el.asInstanceOf[js.Dynamic].getScreenCTM().asInstanceOf[dom.SVGMatrix])
    val layerCtm = Option(layer.asInstanceOf[js.Dynamic].getScreenCTM().asInstanceOf[dom.SVGMatrix])
    (elemCtm, layerCtm) match
      case (Some(e), Some(l)) =>
        val p = new DOMPoint(x, y).matrixTransform(e).matrixTransform(l.inverse())
        (p.x, p.y)
      case _ => (x, y)

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
      mainGroup:         dom.Element,
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
    val layer = layerOf(mainGroup)
    layer.querySelectorAllT[dom.Element](s"g.$badgeClass").foreach(_.remove()) // one decoration per layout, never two
    def addBadge(el: dom.Element, cx: Double, cy: Double, label: String, cls: String, tooltip: String, onToggle: () => Unit): dom.Element =
      val b        = badge(label, cls, tooltip, onToggle)
      val (lx, ly) = toLayer(el, layer, cx, cy)
      layer.appendChild(b)
      place(b, lx, ly, layer)
      b
    if concealed.nonEmpty || collapsed.nonEmpty then
      mainGroup.querySelectorAllT[dom.Element](strategy.nodeSelector).filterNot(isGhost).foreach { el =>
        val id = strategy.extractNodeId(el)
        lazy val bb = el.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
        concealed.get(id).foreach { (succ, pred) =>
          val cy = bb.y + bb.height / 2.0
          if succ > 0 then
            addBadge(el, bb.x + bb.width, cy, countLabel(succ), badgeClass, s"$succ hidden successor(s) — click to show", () => onToggleConcealed(id, true))
          if pred > 0 then
            addBadge(el, bb.x, cy, countLabel(pred), badgeClass, s"$pred hidden predecessor(s) — click to show", () => onToggleConcealed(id, false))
        }
        collapsed.get(id).foreach { members =>
          addBadge(el, bb.x + bb.width, bb.y, countLabel(members), s"$badgeClass $collapseClass", s"$members member(s) — click to expand", () => onToggleCollapsed(id))
        }
      }
    // Every rendered cluster is a group that CAN collapse — the affordance the
    // collapsed box's count badge promises in reverse. Same corner, so the
    // control stays put when the group folds.
    mainGroup.querySelectorAllT[dom.Element](strategy.clusterSelector).filterNot(isGhost).foreach { el =>
      val gid = strategy.extractGroupId(el)
      val bb  = el.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
      val b = addBadge(el, bb.x + bb.width, bb.y, "−", s"$badgeClass $foldClass", "Collapse group into one box", () => onCollapseGroup(gid))
      // The badge no longer sits inside the cluster it folds, so it carries the
      // group's id and [[reflectSelection]] does what descendant CSS did.
      b.setAttribute(gidAttr, gid.value)
    }

  /** Show the fold badges of the selected groups and no others — the "−" is an
    * ACTION with no information content, so it earns its pixels only while its
    * group is the current subject. (The count badges stay always-on: they
    * report something.)
    */
  def reflectSelection(mainGroup: dom.Element, selected: Set[GroupId]): Unit =
    val ids = selected.map(_.value)
    mainGroup.querySelectorAllT[dom.Element](s"g.$foldClass").foreach { b =>
      val gid = b.getAttribute(gidAttr)
      if gid != null && ids.contains(gid) then b.classList.add(activeClass)
      else b.classList.remove(activeClass)
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
    // gx-decoration: a control standing for an element, not part of its
    // geometry. Nothing measures around it now that badges live in their own
    // layer, but the marking is what makes that safe to state.
    g.setAttribute("class", s"$cls ${SelectableElement.decorationClass}")

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
