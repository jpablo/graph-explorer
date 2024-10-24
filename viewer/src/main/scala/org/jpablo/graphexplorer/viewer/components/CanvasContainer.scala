package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.selectable.*
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DiGraphAST
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom
import org.scalajs.dom.KeyCode.Backspace

def CanvasContainer(
    state:      ViewerState,
    fitDiagram: EventStream[Unit]
) =
  import state.eventHandlers.updateTranslate

  val startNode = Var[Option[NodeId]](None)
  val endPos = Var[(Double, Double)]((0, 0))
  val isDragging = Var(false)

  div(
    idAttr   := "canvas-container",
    tabIndex := 0,
    fitDiagram --> state.resetView(),
    child <-- state.svgDiagramElement,
    onKeyDown(_.filter(_.keyCode == Backspace).sample(state.diagramSelection.signal)) --> { selection =>
      state.project.hiddenNodesV.update(_ ++ selection)
    },
    onClick --> handleSvgClick(state),
    onWheel.updateTranslate,

    //////////
    onMouseDown --> { event =>
      findSelectableElement(event).foreach:
        case n: NodeElement =>
          dom.console.log(n.toString)
          startNode.set(Some(n.nodeId))
          isDragging.set(true)
        case _ => ()
    },
    onMouseMove --> { event =>
      if isDragging.now() then
        endPos.set((event.clientX, event.clientY))
    },
    onMouseUp(_.withCurrentValueOf(state.fullAST)) --> { (event, fullAST: DiGraphAST) =>
      if isDragging.now() then
        findSelectableElement(event).foreach:
          case n: NodeElement =>
            startNode.now().filter(_ != n.nodeId).foreach: start =>
              state.addEdge(fullAST, start, n.nodeId)
          case _ =>
        startNode.set(None)
        isDragging.set(false)
    },

    //////////

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
        case n: NodeElement =>
          if event.metaKey then
            state.diagramSelection.toggle(n.nodeId)
          else
            state.diagramSelection.set(Set(n.nodeId))
        case e: EdgeElement =>
          e.toArrow.foreach: arrow =>
            state.diagramSelection.handleClickOnArrow(arrow)(event.metaKey)

end handleSvgClick
