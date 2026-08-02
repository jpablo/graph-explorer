package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.{RecordCellBox, SelectedCell}

import scala.scalajs.js

/** Draws the record CELL selection: a highlight rect (plus the cell's port
  * name) over the selected cell. Positioned from MODEL geometry — the engine
  * SVG has no per-field markup (its output is byte-exact vs Graphviz and must
  * stay so), so the cell box is reconstructed from the node group's own bbox
  * centre + the node-local boxes RecordCellOps recomputes with the engine's
  * record layout.
  */
object RecordCellOverlay:
  private val overlayId = "record-cell-overlay"
  private val dropId    = "record-cell-drop-overlay"
  private val SvgNS     = "http://www.w3.org/2000/svg"

  /** The cell outline's width in CLIENT px, written inline rather than left to
    * the stylesheet's flat `stroke-width`.
    *
    * `vector-effect: non-scaling-stroke` keeps a width constant on screen, but
    * Chrome DIVIDES it by devicePixelRatio — so the sheet's 2px rendered as 1px
    * on a Retina display, which is the "almost imperceptible" outline this
    * replaces. ScreenConstant measures the browser's actual behaviour and
    * corrects for it, so this many px is what you get. See
    * ScreenConstant.strokeWidthFor.
    */
  private val SelectedStrokePx = 3.0
  private val DropStrokePx     = 2.5

  def refresh(
      mainGroup: dom.Element,
      strategy:  SelectableElementStrategy,
      cellOpt:   Option[SelectedCell],
      boxesFor:  NodeId => Vector[RecordCellBox]
  ): Unit =
    Option(mainGroup.querySelector(s"#$overlayId")).foreach(_.remove())
    withCellGeometry(mainGroup, strategy, cellOpt.map(c => (c.nodeId, c.path)), boxesFor): (box, cx, cy, nodeBBox) =>
      val g = dom.document.createElementNS(SvgNS, "g")
      g.setAttribute("id", overlayId)
      g.setAttribute("pointer-events", "none")

      // A cell selection lives INSIDE a node, and the node still read as the
      // subject at a glance. Veil the record's own box with the selected cell
      // punched out, so the row is the only part left at full strength. Drawn,
      // not styled: the engine SVG has no per-field markup to dim individually
      // (same reason the cell box is reconstructed from model geometry above).
      // One even-odd path rather than four bands around the cell — bands meet at
      // seams, and two translucent fills overlapping at a corner paint darker.
      val veil = dom.document.createElementNS(SvgNS, "path")
      veil.setAttribute("class", "record-cell-veil")
      veil.setAttribute("fill-rule", "evenodd")
      veil.setAttribute("d", ringPath(nodeBBox, cx + box.llx, cy - box.ury, box.width, box.height))
      g.appendChild(veil)

      val rect = cellRect(box, cx, cy, "record-cell-selected")
      rect.asInstanceOf[js.Dynamic].style.strokeWidth = s"${ScreenConstant.strokeWidthFor(SelectedStrokePx)}px"
      g.appendChild(rect)

      box.port.foreach: p =>
        val label = dom.document.createElementNS(SvgNS, "text")
        label.setAttribute("class", "record-cell-port")
        label.setAttribute("x", (cx + box.llx + 2).toString)
        label.setAttribute("y", (cy - box.ury - 3).toString)
        label.textContent = s"<$p>"
        g.appendChild(label)

      insertBelowRaised(mainGroup, g)

  /** Overlays park BELOW anything SelectionZOrder has lifted, and below the
    * badge layer.
    *
    * The veil is a record-sized translucent slab. Appended last it covered a
    * SELECTED arrow crossing the record — the arrow the selection was pointing
    * at, dimmed by the marker for a different selection. The slot is computed
    * from the DOM at insertion time rather than fixed, so it does not matter
    * whether a raise or an overlay refresh runs last: both orders converge on
    * veil < raised arrows < badge layer.
    */
  private def insertBelowRaised(mainGroup: dom.Element, g: dom.Element): Unit =
    val anchor =
      Option(mainGroup.querySelector(s".${SelectionZOrder.raisedClass}"))
        .orElse(Option(mainGroup.querySelector("g.gx-badge-layer")))
    mainGroup.insertBefore(g, anchor.orNull)

  /** The attach target while an arrow drag hovers a record: a dashed outline
    * on the cell under the pointer. Pass None to clear. */
  def refreshDropHighlight(
      mainGroup: dom.Element,
      strategy:  SelectableElementStrategy,
      cellOpt:   Option[(NodeId, List[Int])],
      boxesFor:  NodeId => Vector[RecordCellBox]
  ): Unit =
    Option(mainGroup.querySelector(s"#$dropId")).foreach(_.remove())
    withCellGeometry(mainGroup, strategy, cellOpt, boxesFor): (box, cx, cy, _) =>
      val g = dom.document.createElementNS(SvgNS, "g")
      g.setAttribute("id", dropId)
      g.setAttribute("pointer-events", "none")
      val rect = cellRect(box, cx, cy, "record-cell-drop")
      // Same HiDPI correction as the selection outline — a drop target you
      // cannot see is worse than one you can.
      rect.asInstanceOf[js.Dynamic].style.strokeWidth = s"${ScreenConstant.strokeWidthFor(DropStrokePx)}px"
      g.appendChild(rect)
      insertBelowRaised(mainGroup, g)

  /** The area between `outer` and the cell rect, as ONE even-odd subpath pair. */
  private def ringPath(outer: dom.SVGRect, x: Double, y: Double, w: Double, h: Double): String =
    s"M${outer.x},${outer.y} h${outer.width} v${outer.height} h${-outer.width} Z " +
      s"M$x,$y h$w v$h h${-w} Z"

  private def withCellGeometry(
      mainGroup: dom.Element,
      strategy:  SelectableElementStrategy,
      cellOpt:   Option[(NodeId, List[Int])],
      boxesFor:  NodeId => Vector[RecordCellBox]
  )(draw: (RecordCellBox, Double, Double, dom.SVGRect) => Unit): Unit =
    for
      (nodeId, path) <- cellOpt
      elem           <- SelectableElement.query(mainGroup, ElementIds(Set(nodeId)), strategy).headOption
      box            <- boxesFor(nodeId).find(_.path == path)
    do
      val bbox = ownGeometryBBox(elem.ref)
      draw(box, bbox.x + bbox.width / 2, bbox.y + bbox.height / 2, bbox)

  private def cellRect(box: RecordCellBox, cx: Double, cy: Double, cls: String): dom.Element =
    val rect = dom.document.createElementNS(SvgNS, "rect")
    rect.setAttribute("class", cls)
    // gv frame is y-up around the node centre; the svg frame negates y.
    rect.setAttribute("x", (cx + box.llx).toString)
    rect.setAttribute("y", (cy - box.ury).toString)
    rect.setAttribute("width", box.width.toString)
    rect.setAttribute("height", box.height.toString)
    rect

  /** The element's OWN geometry: any decoration riding the element is
    * display:none'd for the measurement (same trick as
    * SelectableElement.SelectedRect). */
  def ownGeometryBBox(ref: dom.Element): dom.SVGRect =
    val decorations = ref.querySelectorAllT[dom.Element](s".${SelectableElement.decorationClass}")
    decorations.foreach(_.setAttribute("display", "none"))
    val bbox = ref.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
    decorations.foreach(_.removeAttribute("display"))
    bbox
