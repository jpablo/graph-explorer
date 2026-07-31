package org.jpablo.graphexplorer.viewer.state.mouseActions

import org.jpablo.graphexplorer.viewer.components.selection.{NodeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{ArrowFromSourceToPointer, NewArrowControl}
import org.jpablo.graphexplorer.viewer.domUtils.{DomEvent, elementsFromPoint}
import org.jpablo.graphexplorer.viewer.models.{ArrowDirection, NodeId}
import org.jpablo.graphexplorer.viewer.state.DiagramSelectionOps.findClosestElementId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.state.mouseActions.MouseAction.{AddNewArrowAction, Inactive}
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, MouseActionRect}
import org.scalajs.dom.DOMRect

import scala.scalajs.js

def pointInsideBox(
    pt:   (x: Double, y: Double),
    bbox: DOMRect
): Boolean =
  pt.x >= bbox.left &&
    pt.x <= bbox.right &&
    pt.y >= bbox.top &&
    pt.y <= bbox.bottom

trait AddNewArrowOps:
  this: ViewerState =>

  def handleAddNewArrowMouseUp(ev: dom.MouseEvent, action: AddNewArrowAction): Unit =
    val current = selection.now()
    val start   = action.originator

    // Check if the mouse release point (not the selection rectangle) is inside the source node's bounding box
    val isMouseInsideSourceNode = pointInsideBox(pt = (ev.clientX, ev.clientY), bbox = start.ref.getBoundingClientRect())
    // Single selection and mouse released on the source node: add a self-loop
    if current.size == 1 && isMouseInsideSourceNode then
      start.nodeId.foreach: nodeId =>
        // A record self-loop can still be cell-to-cell: the drag started from
        // the selected cell, the drop point picks the other end's cell.
        val dropCell = recordCells.cellPathAtClientPoint(nodeId, ev.clientX, ev.clientY)
        addArrow(nodeId, nodeId, action.sourceCellPath, dropCell)
    else if current.size == 2 then
      (current - start.elementId).head.asNodeId.foreach: end =>
        // Dropping on a record attaches to the CELL under the pointer (its
        // port, minted on commit when the cell has none).
        val dropCell = recordCells.cellPathAtClientPoint(end, ev.clientX, ev.clientY)
        action.direction match
          case ArrowDirection.forward  => addArrow(start.nodeId.get, end, action.sourceCellPath, dropCell)
          case ArrowDirection.backward => addArrow(end, start.nodeId.get, dropCell, action.sourceCellPath)

  def onAddNewArrowAction(action: AddNewArrowAction) =
    selectWithClosestNode(
      start = action.originator,
      elementsFromRectEnd = dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)
    )

  def selectWithClosestNode(
      start:               SelectableElement,
      elementsFromRectEnd: js.Array[dom.Element]
  ) =
    // Make sure only start or (start,end) nodes are selected when creating a new arrow
    // For now only allow a line selection into nodes
    val strategy = selectionStrategyNow()
    findClosestElementId(elementsFromRectEnd, strategy = strategy, selector = Some(strategy.nodeSelector)) match
      case Some(elementId) => selection.set1(Set(start.elementId, elementId))
      case None               => selection.set2(start.elementId)

  val dirs = ArrowDirection.values.toSeq

  def handleNewArrowControls(parent: dom.svg.G, selection: Option[SelectableElement], action: MouseAction): Unit =
    // Read the badge model ONCE, and read the SAME one CountBadges drew from:
    // a control decides where to stand by which edges carry a count, so the two
    // must never disagree about that.
    val concealed = concealedCountsNow()
    val controls =
      for
        elem <- selection.toSeq
        c    <- dirs.flatMap(buildNewArrowControl(parent, elem, action, _, concealed))
      yield c

    // Always clear previous controls to avoid duplicates lingering after selection changes
    parent.querySelectorAll("g.new-arrow-control").foreach(_.remove())
    controls.foreach(parent.appendChild)

  def buildNewArrowControl(
      parent:       dom.svg.G,
      selectedElem: SelectableElement,
      action:       MouseAction,
      direction:    ArrowDirection,
      /** Concealed-neighbor counts (successors, predecessors) — the badge
        * model, consulted here only to learn which edges are already taken. */
      concealed: Map[NodeId, (Int, Int)] = Map.empty
  ): Option[dom.svg.G] =
    val showControl =
      action match
        case Inactive             => true
        case a: AddNewArrowAction => a.rect.isEmpty
        case _                    => false

    selectedElem match
      case elem: NodeElement if showControl =>
        val parentCtm = Option(parent.asInstanceOf[js.Dynamic].getScreenCTM().asInstanceOf[dom.SVGMatrix])
        // (successors, predecessors): the successor count badges the RIGHT
        // edge and the predecessor count the LEFT, whatever the rankdir —
        // those are the edges a control has to step around.
        val (succ, pred) = elem.nodeId.flatMap(concealed.get).getOrElse((0, 0))
        val control =
          NewArrowControl(
            elem,
            graphRankDirNow,
            direction,
            clientSize,
            screenCtm = parentCtm,
            occupiedSides = (left = pred > 0, right = succ > 0)
          ).ref

        control.addEventListener(
          DomEvent.mousedown,
          (ev: dom.MouseEvent) => {
            ev.stopPropagation()
            val pos = ClientPoint(ev.clientX, ev.clientY)
            // Capture the selected CELL now: the drag's live selection updates
            // will prune it (two elements selected ≠ single record).
            val sourceCell = selectedCellV.now().filter(_.nodeId == elem.elementId).map(_.path)
            mouseAction.start(AddNewArrowAction(MouseActionRect(pos, pos, shift = false), selectedElem, direction, sourceCell))
          }
        )

        control.addEventListener(
          DomEvent.mouseup,
          (ev: dom.MouseEvent) => {
            ev.stopPropagation()
            mouseAction.inactive()
            createNodeMaybePrompt(direction = direction)
          }
        )

        Some(control)
      case _ => None

  def addArrowFromSourceToPointer(rootGroup: dom.svg.G, action: AddNewArrowAction): Unit =
    rootGroup.appendChild(ArrowFromSourceToPointer(action, rootGroup).ref)
