package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.selection.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom

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
    onKeyDown.mapToEvent --> state.handleKeyDown,
    onClick.preventDefault --> state.diagramSelection.handleSvgClick,
    onWheel.updateTranslate,

    // --------------------------------
    onMouseDown --> { event =>
      findSelectableElement(event).foreach:
        case (endNodeId: NodeId, _) =>
          val clientCoords = (event.clientX, event.clientY)
          Var.set(
            state.startNode  -> Some(endNodeId, clientCoords),
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
          case (endNodeId: NodeId, _) =>
            state.startNode.now().map(_._1)
              .filter(_ != endNodeId)
              .foreach: startNodeId =>
                state.addEdge(startNodeId, endNodeId)
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

def findSelectableElement(event: dom.MouseEvent): Option[(NodeId | Option[Arrow], Boolean)] =
  event.target
    .asInstanceOf[dom.Element]
    .parentNodes
    .takeWhile(_.isInstanceOf[dom.SVGElement])
    .map(SelectableElement.fromDomElement)
    .collectFirst { case Some(g) => g }
    .map:
      case n: NodeElement => (n.nodeId, event.metaKey)
      case e: EdgeElement => (e.toArrow, event.metaKey)
