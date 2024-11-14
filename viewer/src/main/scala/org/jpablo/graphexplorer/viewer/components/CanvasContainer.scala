package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.selection.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.KeyCode.Backspace

def CanvasContainer(
    state:      ViewerState,
    fitDiagram: EventStream[Unit]
) =
  import state.eventHandlers.updateTranslate

  div(
    idAttr   := "canvas-container",
    tabIndex := 0,
    fitDiagram --> state.resetView(),
    child <-- state.svgDiagramElement,
    onKeyDown(_.filter(_.keyCode == Backspace).sample(state.diagramSelection.signal)) --> { selection =>
      state.project.hiddenNodes.update(_ ++ selection)
    },
    onClick --> handleSvgClick(state),
    onWheel.updateTranslate,

    // --------------------------------
    onMouseDown --> { event =>
      findSelectableElement(event).foreach:
        case n: NodeElement =>
          val clientCoords = (event.clientX, event.clientY)
          Var.set(
            state.startNode  -> Some(n.nodeId, clientCoords),
            state.endPos     -> clientCoords,
            state.isDragging -> true
          )
        case _ => ()
    },
    onMouseMove --> { event =>
      if state.isDragging.now() then
        state.endPos.set((event.clientX, event.clientY))
    },
    onMouseUp --> { event =>
      if state.isDragging.now() then
        findSelectableElement(event).foreach:
          case endNode: NodeElement =>
            state.startNode.now().map(_._1)
              .filter(_ != endNode.nodeId)
              .foreach: startNodeId =>
                state.addEdge(startNodeId, endNode.nodeId)
          case _ => ()
        Var.set(
          state.startNode  -> None,
          state.isDragging -> false
        )
    },
    // --------------------------------

    inContext: thisNode =>
      // Sync svg style with internal state
      state.diagramSelection.signal --> { selectedNodes =>
        for elem <- SelectableElement.findAll(thisNode.ref) do
          if elem.nodeId in selectedNodes then
            elem.select()
          else
            elem.unselect()
      }
  )

private def findSelectableElement(event: dom.MouseEvent): Option[SelectableElement] =
  event.target
    .asInstanceOf[dom.Element]
    .parentNodes
    .takeWhile(_.isInstanceOf[dom.SVGElement])
    .map(SelectableElement.fromDomElement)
    .collectFirst { case Some(g) => g }

private def handleSvgClick(state: ViewerState)(event: dom.MouseEvent): Unit =
  findSelectableElement(event) match
    case None => state.diagramSelection.clear()
    case Some(element) => element match
        case n: NodeElement => state.diagramSelection.handleClickOnNode(n.nodeId)(event.metaKey)
        case e: EdgeElement =>
          e.toArrow.foreach: arrow =>
            state.diagramSelection.handleClickOnArrow(arrow)(event.metaKey)

end handleSvgClick
