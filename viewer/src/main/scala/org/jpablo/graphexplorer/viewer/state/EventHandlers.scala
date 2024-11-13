package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*
import com.raquo.laminar.modifiers.Binder.Base
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DotAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.ViewerState.handleWheel
import org.scalajs.dom.SVGSVGElement
import upickle.default.*

class EventHandlers(
    diagramSelection:  DiagramSelectionOps,
    project:           ProjectOps,
    hiddenNodesS:      Signal[Set[NodeId]],
    svgDiagramElement: Signal[ReactiveSvgElement[SVGSVGElement]],
    sourceFlow:        SourceFlow,
    hiddenNodes:       HiddenNodesOps,
    zoomValue:         Var[Double],
    translateXY:       Var[Point2d[SvgUnit]]
):

  val allNodeIds: Signal[Set[NodeId]] =
    sourceFlow.fullGraph.map(_.allNodeIds)
  
  /** Modify `hiddenNodes` based on the given function `f`
    */
  private def updateHiddenNodes[E <: dom.Event](
      ep: EventProp[E]
  )(f: (HiddenNodes, Set[NodeId], ViewerGraph) => HiddenNodes) =
    ep(_.sample(sourceFlow.fullGraph.combineWith(diagramSelection.signal))) --> {
      (g: ViewerGraph, selection: Set[NodeId]) =>
        project.hiddenNodes.update(f(_, selection, g))
    }

  private val svgDotDiagram: Signal[SvgDotDiagram] =
    svgDiagramElement.map(SvgDotDiagram.apply)

  extension [E <: dom.Event](ev: EventProp[E])
    def hideSelectedNodes =
      updateHiddenNodes(ev)((hidden, sel, _) => hidden ++ sel)

    def hideNonSelectedNodes =
      updateHiddenNodes(ev)((hidden, sel, g) => hidden ++ (g.allNodeIds -- sel))

    def showAllSuccessors =
      updateHiddenNodes(ev)((hidden, sel, g) => hidden -- g.allSuccessorsGraph(sel).allNodeIds)

    def showDirectSuccessors =
      updateHiddenNodes(ev)((hidden, sel, g) => hidden -- g.directSuccessorsGraph(sel).allNodeIds)

    def showAllPredecessors =
      updateHiddenNodes(ev)((hidden, sel, g) => hidden -- g.allPredecessorsGraph(sel).allNodeIds)

    def showDirectPredecessors =
      updateHiddenNodes(ev)((hidden, sel, g) => hidden -- g.directPredecessorsGraph(sel).allNodeIds)

    def selectSuccessors =
      ev(_.sample(sourceFlow.fullGraph, hiddenNodesS)) --> diagramSelection.selectSuccessors.tupled

    def selectPredecessors =
      ev(_.sample(sourceFlow.fullGraph, hiddenNodesS)) --> diagramSelection.selectPredecessors.tupled

    def selectDirectSuccessors =
      ev(_.sample(sourceFlow.fullGraph, hiddenNodesS)) --> diagramSelection.selectDirectSuccessors.tupled

    def selectDirectPredecessors =
      ev(_.sample(sourceFlow.fullGraph, hiddenNodesS)) --> diagramSelection.selectDirectPredecessors.tupled

    def copyAsFullDiagramSVG(writeText: String => Any): Base =
      ev(_.sample(svgDotDiagram)) --> { svgDiagram => writeText(svgDiagram.toSVGText) }

    def copySelectionAsSVG(writeText: String => Any) =
      ev(_.sample(svgDotDiagram, diagramSelection.signal)) --> { (svgDiagram: SvgDotDiagram, canvasSelection) =>
        writeText(svgDiagram.toSVGTextWithIds(canvasSelection))
      }

    def copyAsDOT(writeText: String => Any) =
      ev(_.sample(sourceFlow.visibleDOT)) --> { dot => writeText(dot.value) }

    def copyAsJSON(writeText: String => Any) =
      ev(_.sample(sourceFlow.visibleAST)) --> { ast => writeText(writeJs(ast).toString) }

    def keepRootsOnly =
      updateHiddenNodes(ev)((_, _, g) => g.allNodeIds -- g.roots)

    def hideAllNodes =
      ev(_.sample(allNodeIds).map(_.toSeq)) --> (hiddenNodes.extend(_))

    def updateTranslate(using E <:< dom.WheelEvent): Base =
      ev(_.withCurrentValueOf(svgDiagramElement)) --> (handleWheel(zoomValue, translateXY)(_, _))
