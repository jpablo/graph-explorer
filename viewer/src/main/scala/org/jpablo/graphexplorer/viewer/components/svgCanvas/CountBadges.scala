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

  /** On a fold badge whose group is UNDER THE POINTER — the stylesheet shows
    * only those. It used to be read off the DOM (`.selected > .gx-fold-badge`),
    * which the move out of the cluster group took away; then off the selection,
    * which meant you had to select a group to discover it could fold at all. */
  val activeClass = "gx-badge-active"

  private val layerClass = "gx-badge-layer"
  private val gidAttr    = "data-gid"

  /** Install-once marker for the delegated hover listener, and the group the
    * pointer is currently over — both parked on the svg rather than in object
    * state, so nothing survives a diagram switch that shouldn't. */
  private val hoverInstalledAttr = "data-gx-fold-hover"
  private val hoveredAttr        = "data-gx-fold-hovered"

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

  /** A badge is anchored ON the point it marks, with no offset of its own. */
  private def place(g: dom.Element, cx: Double, cy: Double, reference: dom.Element): Unit =
    val anchored =
      ScreenConstant.Anchored(cx, cy, oxPx = 0, oyPx = 0, sizePx = clientDiameter, designBox = designRadius * 2)
    // A missing CTM (hidden/pre-layout svg) leaves the badge at scale 1 for
    // now; the next refit corrects it rather than freezing it diagram-sized.
    ScreenConstant.place(g, anchored, ScreenConstant.userPerPx(reference).getOrElse(1.0))

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
      // group's id and [[installHover]] does what descendant CSS did.
      b.setAttribute(gidAttr, gid.value)
    }
    // These are BRAND NEW badges, with no active class on any of them. If the
    // pointer is resting inside a group while the layout re-renders (folding a
    // sibling does exactly that), the badge under it would go dark and stay dark
    // until the pointer crossed a boundary — so re-apply what hover already knows.
    Option(mainGroup.closest("svg")).foreach: root =>
      Option(root.getAttribute(hoveredAttr)).filter(_.nonEmpty).foreach(applyHovered(root, _))

  /** Reveal a group's fold badge while the pointer is over that group.
    *
    * The "−" is an ACTION with no information content, so it earns its pixels
    * only while you are looking at its group — but keying that to SELECTION got
    * the affordance backwards: you had to select a group to find out it could
    * fold, and folding one meant selecting it first. Hover asks nothing and
    * reaches every group, selected or not. (The count badges stay always-on:
    * they report something.)
    *
    * Delegated from the svg rather than a listener per cluster: `decorate` runs
    * once per layout and the cluster elements it walks are replaced wholesale by
    * the next render, so per-element listeners would need tracking and removal.
    * One listener on the svg outlives every re-render — hence the install-once
    * flag rather than an add/remove dance.
    */
  def installHover(mainGroup: dom.Element, strategy: SelectableElementStrategy): Unit =
    val root = Option(mainGroup.closest("svg")).getOrElse(mainGroup)
    if root.getAttribute(hoverInstalledAttr) == null then
      root.setAttribute(hoverInstalledAttr, "1")
      // `mouseover` bubbles and re-fires on every boundary crossing, so entering
      // ANY element reports the new target — including the empty canvas, which
      // is how the badge learns to hide again. `mouseleave` covers the pointer
      // leaving the svg altogether, where no further mouseover ever arrives.
      root.addEventListener(
        "mouseover",
        ((e: dom.Event) => setHovered(root, groupUnder(e.target.asInstanceOf[dom.Element], strategy))): js.Function1[dom.Event, Unit]
      )
      root.addEventListener("mouseleave", ((_: dom.Event) => setHovered(root, None)): js.Function1[dom.Event, Unit])

  /** Which group the pointer counts as being over.
    *
    * The badge is NOT a descendant of the cluster it folds — it rides the
    * overlay layer so it paints above the edges — so `closest(clusterSelector)`
    * from the badge finds nothing. That matters because the badge STRADDLES the
    * group's border: reaching for it means leaving the group, and the control
    * would vanish from under the cursor on the way to the click. So a fold badge
    * counts as hovering its own group, and is checked first.
    */
  private def groupUnder(target: dom.Element, strategy: SelectableElementStrategy): Option[String] =
    Option(target.closest(s"g.$foldClass"))
      .map(_.getAttribute(gidAttr))
      .orElse(Option(target.closest(strategy.clusterSelector)).map(strategy.extractGroupId(_).value))
      .filter(_ != null)

  /** Memoized on the svg so a drag across a dozen nodes inside one cluster does
    * not rewrite every badge's class list a dozen times. */
  private def setHovered(root: dom.Element, gid: Option[String]): Unit =
    val next = gid.getOrElse("")
    if root.getAttribute(hoveredAttr) != next then
      root.setAttribute(hoveredAttr, next)
      applyHovered(root, next)

  private def applyHovered(root: dom.Element, gid: String): Unit =
    root.querySelectorAllT[dom.Element](s"g.$foldClass").foreach { b =>
      if gid.nonEmpty && b.getAttribute(gidAttr) == gid then b.classList.add(activeClass)
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
