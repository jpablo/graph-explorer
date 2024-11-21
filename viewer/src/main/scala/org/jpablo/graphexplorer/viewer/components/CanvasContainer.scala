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
        case (endNodeId: NodeId, _) => state.handleMouseDown(endNodeId, (event.clientX, event.clientY))
        case _                      => ()
    },
    onMouseMove --> { event =>
      state.handleMouseMove((event.clientX, event.clientY), event.buttons)
    },
    onMouseUp --> { event =>
      state.handleMouseUp(
        findSelectableElement(event).map(_._1)
          .collect { case id: NodeId => id }
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
