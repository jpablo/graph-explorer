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

  def refresh(
      mainGroup: dom.Element,
      strategy:  SelectableElementStrategy,
      cellOpt:   Option[SelectedCell],
      boxesFor:  NodeId => Vector[RecordCellBox]
  ): Unit =
    Option(mainGroup.querySelector(s"#$overlayId")).foreach(_.remove())
    withCellGeometry(mainGroup, strategy, cellOpt.map(c => (c.nodeId, c.path)), boxesFor): (box, cx, cy) =>
      val g = dom.document.createElementNS(SvgNS, "g")
      g.setAttribute("id", overlayId)
      g.setAttribute("pointer-events", "none")

      val rect = cellRect(box, cx, cy, "record-cell-selected")
      g.appendChild(rect)

      box.port.foreach: p =>
        val label = dom.document.createElementNS(SvgNS, "text")
        label.setAttribute("class", "record-cell-port")
        label.setAttribute("x", (cx + box.llx + 2).toString)
        label.setAttribute("y", (cy - box.ury - 3).toString)
        label.textContent = s"<$p>"
        g.appendChild(label)

      mainGroup.appendChild(g)

  /** The attach target while an arrow drag hovers a record: a dashed outline
    * on the cell under the pointer. Pass None to clear. */
  def refreshDropHighlight(
      mainGroup: dom.Element,
      strategy:  SelectableElementStrategy,
      cellOpt:   Option[(NodeId, List[Int])],
      boxesFor:  NodeId => Vector[RecordCellBox]
  ): Unit =
    Option(mainGroup.querySelector(s"#$dropId")).foreach(_.remove())
    withCellGeometry(mainGroup, strategy, cellOpt, boxesFor): (box, cx, cy) =>
      val g = dom.document.createElementNS(SvgNS, "g")
      g.setAttribute("id", dropId)
      g.setAttribute("pointer-events", "none")
      g.appendChild(cellRect(box, cx, cy, "record-cell-drop"))
      mainGroup.appendChild(g)

  private def withCellGeometry(
      mainGroup: dom.Element,
      strategy:  SelectableElementStrategy,
      cellOpt:   Option[(NodeId, List[Int])],
      boxesFor:  NodeId => Vector[RecordCellBox]
  )(draw: (RecordCellBox, Double, Double) => Unit): Unit =
    for
      (nodeId, path) <- cellOpt
      elem           <- SelectableElement.query(mainGroup, ElementIds(Set(nodeId)), strategy).headOption
      box            <- boxesFor(nodeId).find(_.path == path)
    do
      val bbox = ownGeometryBBox(elem.ref)
      draw(box, bbox.x + bbox.width / 2, bbox.y + bbox.height / 2)

  private def cellRect(box: RecordCellBox, cx: Double, cy: Double, cls: String): dom.Element =
    val rect = dom.document.createElementNS(SvgNS, "rect")
    rect.setAttribute("class", cls)
    // gv frame is y-up around the node centre; the svg frame negates y.
    rect.setAttribute("x", (cx + box.llx).toString)
    rect.setAttribute("y", (cy - box.ury).toString)
    rect.setAttribute("width", box.width.toString)
    rect.setAttribute("height", box.height.toString)
    rect

  /** The element's OWN geometry: badges/decorations are display:none'd for the
    * measurement (same trick as SelectableElement.SelectedRect). */
  def ownGeometryBBox(ref: dom.Element): dom.SVGRect =
    val decorations = ref.querySelectorAllT[dom.Element](s".${SelectableElement.decorationClass}")
    decorations.foreach(_.setAttribute("display", "none"))
    val bbox = ref.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
    decorations.foreach(_.removeAttribute("display"))
    bbox
