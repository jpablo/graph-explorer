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
  private val SvgNS     = "http://www.w3.org/2000/svg"

  def refresh(
      mainGroup: dom.Element,
      strategy:  SelectableElementStrategy,
      cellOpt:   Option[SelectedCell],
      boxesFor:  NodeId => Vector[RecordCellBox]
  ): Unit =
    Option(mainGroup.querySelector(s"#$overlayId")).foreach(_.remove())
    for
      cell <- cellOpt
      elem <- SelectableElement.query(mainGroup, ElementIds(Set(cell.nodeId)), strategy).headOption
      box  <- boxesFor(cell.nodeId).find(_.path == cell.path)
    do
      val bbox = ownGeometryBBox(elem.ref)
      val cx   = bbox.x + bbox.width / 2
      val cy   = bbox.y + bbox.height / 2

      val g = dom.document.createElementNS(SvgNS, "g")
      g.setAttribute("id", overlayId)
      g.setAttribute("pointer-events", "none")

      val rect = dom.document.createElementNS(SvgNS, "rect")
      rect.setAttribute("class", "record-cell-selected")
      // gv frame is y-up around the node centre; the svg frame negates y.
      rect.setAttribute("x", (cx + box.llx).toString)
      rect.setAttribute("y", (cy - box.ury).toString)
      rect.setAttribute("width", box.width.toString)
      rect.setAttribute("height", box.height.toString)
      g.appendChild(rect)

      box.port.foreach: p =>
        val label = dom.document.createElementNS(SvgNS, "text")
        label.setAttribute("class", "record-cell-port")
        label.setAttribute("x", (cx + box.llx + 2).toString)
        label.setAttribute("y", (cy - box.ury - 3).toString)
        label.textContent = s"<$p>"
        g.appendChild(label)

      mainGroup.appendChild(g)

  /** The element's OWN geometry: badges/decorations are display:none'd for the
    * measurement (same trick as SelectableElement.SelectedRect). */
  def ownGeometryBBox(ref: dom.Element): dom.SVGRect =
    val decorations = ref.querySelectorAllT[dom.Element](s".${SelectableElement.decorationClass}")
    decorations.foreach(_.setAttribute("display", "none"))
    val bbox = ref.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
    decorations.foreach(_.removeAttribute("display"))
    bbox
