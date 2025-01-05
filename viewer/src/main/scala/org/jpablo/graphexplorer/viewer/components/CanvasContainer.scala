package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.selection.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.domUtils.elementsFromPoint
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.logging.withLog


def CanvasContainer(
    state:      ViewerState,
    fitDiagram: EventStream[Unit]
) =
  import state.eventHandlers.updateTranslate

  div(
    idAttr   := "canvas-container",
    tabIndex := 0,
    fitDiagram --> state.resetView(),
    child <-- state.svgDiagramElement.tapEach { e =>
      withLog("[CanvasContainer]: svgDiagramElement")(())
    },
    onKeyDown.mapToEvent --> state.handleKeyDown,
    onClick.preventDefault --> state.diagramSelection.handleSvgClick,
    onWheel.updateTranslate,

    // --------------------------------
    onMouseDown --> { event =>
      dom.console.log("-------- onMouseDown --------")
      findSelectableElement(event).foreach:
        case (endNodeId: NodeId, _) =>
          state.handleMouseDown(endNodeId, (event.clientX, event.clientY))
        case _ => ()
    },
    onMouseMove --> { event =>
      state.handleMouseMove(event.buttons, (event.clientX, event.clientY))
    },
    onMouseUp --> { event =>
      dom.console.log("-------- onMouseUp --------")
      findSelectableElement(event).map(_._1) match
        case Some(id: NodeId) => state.handleMouseUp(Some(id))
        case _                => state.handleMouseUp(None)
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
  dom.console.log("-------- findSelectableElement --------")
  val elements = dom.document.elementsFromPoint(event.clientX, event.clientY)

  // Filter SVG elements and include their ancestor nodes
  val svgElements = elements.toArray
    .filter(_.namespaceURI == "http://www.w3.org/2000/svg")
    .flatMap { element =>
      // Get the closest parent 'g' element that represents a node or edge
      val parentG = element.closest("g.node, g.edge")
      if parentG != null then Some(parentG) else None
    }
    .distinct // Remove duplicates

  svgElements
    .map(SelectableElement.fromDomElement)
    .collectFirst { case Some(g) => g }
    .map:
      case n: NodeElement => (n.nodeId, event.metaKey)
      case e: EdgeElement => (e.toArrow, event.metaKey)
