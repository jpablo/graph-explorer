package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.SvgMods
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.components.toSvgPair
import org.jpablo.graphexplorer.viewer.domUtils.SvgUtils.getTranslate
import org.jpablo.graphexplorer.viewer.domUtils.{SvgUtils, elementsFromPoint}
import org.jpablo.graphexplorer.viewer.models.ElementId
import org.jpablo.graphexplorer.viewer.state.{AddNewArrowOps, DiagramSelectionOps, MoveArrowStartOps}
import org.jpablo.graphexplorer.viewer.utils.{BBox, ClientPoint, SvgPoint}

// A SvgCanvas is an SVG element with interactive elements handled by Laminar.
object SvgCanvas:

  extension (e: dom.MouseEvent)
    def clientCoords    = (ClientPoint(e.clientX, e.clientY), e.shiftKey)
    def leftButton      = e.button == 0
    def leftButtonMoved = e.buttons == 1

  // rawSvg is the SVG element as it comes from DOT
  def apply(
      rawSvg:       dom.svg.SVG,
      transform:    Signal[String],
      selectionOps: DiagramSelectionOps & AddNewArrowOps & MoveArrowStartOps,
      updateLabel:  (ElementId, String) => Unit
  ): ReactiveSvgElement[dom.svg.SVG] =
    import selectionOps.selection

    val firstGroup: dom.svg.G =
      val g0 = rawSvg.querySelector("g")
      (if g0 == null then dom.document.createElement("g") else g0).asInstanceOf[dom.svg.G]

    // --------------------------------------------------------
    // The top level <svg> element
    // --------------------------------------------------------
    val viewBox = rawSvg.viewBox.baseVal
    val tr      = getTranslate(firstGroup)
    val bbox    = BBox(viewBox.x - tr.x, viewBox.y - tr.y, viewBox.width, viewBox.height)

    emptySvg(
      viewBox = bbox,
      TopLevelGroup(firstGroup, transform, selectionOps)
    ).amendThis { topLevelSvg =>
      val selectableElements = SelectableElement.findAll(topLevelSvg.ref)

      val selectionGroups =
        selection.signal
          .scanLeft(x => (x, x)):
            case ((_, curr), next) => (curr, next)
          .map: (curr, next) =>
            val toUnselect = curr.filter(id => !next.contains(id))
            val toSelect   = next.filter(id => !curr.contains(id))
            (SelectableElement.query(topLevelSvg.ref, toUnselect), SelectableElement.query(topLevelSvg.ref, toSelect))

      Seq(
        // --------------------------------------------------------
        //   draw selection rect
        // --------------------------------------------------------
        child.maybe <-- DrawSelectionRect(
          selection.mouseAction.extendSelectionAction
            .map(_.map(_.rect.toSvgPair(topLevelSvg.ref.getScreenCTM())))
        ),
        // --------------------------------------------------------
        //   select elements intersecting selectionRec
        // --------------------------------------------------------
        selection.mouseAction.extendSelectionAction --> { actionO =>
          for action <- actionO do
            selection.selectExtendSelectionOverlappingElements(
              action.rect,
              selectableElements,
              dom.document.elementsFromPoint(action.rect.end.x, action.rect.end.y)
            )
        },
        selection.mouseAction.addNewArrowAction --> selectionOps.onAddNewArrowAction,
        selection.mouseAction.moveArrowStartAction --> selectionOps.onMoveArrowStart,
        // --------------------------------------------------------
        //   synchronize svg elements with diagramSelection
        // --------------------------------------------------------
        selectionGroups --> { (toUnselect: Seq[SelectableElement], toSelect: Seq[SelectableElement]) =>
          toUnselect.foreach(_.unselect())
          toSelect.foreach(_.select())
        }
      )
    }
  end apply

  // --------------------------------------------------------
  // The top level <g> element
  // --------------------------------------------------------
  def TopLevelGroup(
      firstGroup:   dom.svg.G,
      transform:    Signal[String],
      selectionOps: DiagramSelectionOps & AddNewArrowOps & MoveArrowStartOps,
  ): ReactiveSvgElement[dom.svg.G] =
    import selectionOps.selection
    foreignSvgElement(svg.g, firstGroup)
      .amendThis: group =>
        Seq(
          svg.transform <-- transform,
          // --------------------------------------------------------
          // Action buttons for selected elements:
          //   - "New arrow" button
          //   - Arrow endpoint button
          // --------------------------------------------------------
          children <--
            selection.signal.map: selected =>
              // only show the "New arrow" button if there is exactly one selected element
              if selected.size == 1 then
                for
                  selectedElem <- SelectableElement.query(group.ref, selected).headOption.toSeq
                  btn <- selectionOps.buildNewArrowButton(selectedElem) ++ selectionOps.buildArrowEndpointButton(selectedElem)
                yield btn
              else
                Nil
          ,
          // --------------------------------------------------------
          //   draw dragging arrow
          // --------------------------------------------------------
          child.maybe <-- DraggingArrow(selection.mouseAction.addNewArrowAction, group.ref),
          child.maybe <-- ArrowWithEndpoint(selection.mouseAction.moveArrowStartAction, group.ref),
          child.maybe <-- selectionOps.buildArrowWithEndpoint(group.ref)
        )

  /** Creates a standalone SVG element with the given viewBox
    */
  def emptySvg(viewBox: BBox, mods: SvgMods*): ReactiveSvgElement[dom.svg.SVG] =
    svg.svg(
      svg.xmlns      := "http://www.w3.org/2000/svg",
      svg.xmlnsXlink := "http://www.w3.org/1999/xlink",
      svg.viewBox    := s"${viewBox.x} ${viewBox.y} ${viewBox.width} ${viewBox.height}",
      svg.cls        := "graphviz no-text-select",
      mods
    )

  /** Creates a reactive SVG rectangle element representing the selection box when dragging.
    *
    * @param action
    *   Signal containing the current selection rectangle state
    * @param topLevelSVG
    *   The SVG element that contains the selection
    * @return
    *   Signal containing an optional SVG rect element. The rect is only present when there is an active selection action.
    */
  private def DrawSelectionRect(
      startEnd: Signal[Option[(SvgPoint, SvgPoint)]]
  ): Signal[Option[ReactiveSvgElement[dom.svg.RectElement]]] =
    startEnd.map:
      _.flatMap: (start, end) =>
        Some(
          svg.rect(
            svg.idAttr := "selection-rectangle",
            svg.x      := (start.x min end.x).toString,
            svg.y      := (start.y min end.y).toString,
            svg.width  := math.abs(end.x - start.x).toString,
            svg.height := math.abs(end.y - start.y).toString
          )
        )
