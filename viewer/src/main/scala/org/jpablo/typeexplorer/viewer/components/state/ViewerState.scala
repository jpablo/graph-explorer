package org.jpablo.typeexplorer.viewer.components.state

import com.raquo.airstream.core.{EventStream, Signal}
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.modifiers.Binder.Base
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz.toDot
import org.jpablo.typeexplorer.viewer.components.SvgDiagram
import org.jpablo.typeexplorer.viewer.components.state.ViewerState.ActiveSymbols
import org.jpablo.typeexplorer.viewer.extensions.*
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.ViewerNodeId
import org.scalajs.dom

object ViewerState:
  type ActiveSymbols = Map[ViewerNodeId, Option[SymbolOptions]]

case class ViewerState(
    pageV:      Var[Page],
    graph:      Signal[ViewerGraph],
    renderDot:  String => Signal[SvgDiagram]
):
  // TODO: verify that subscriptions are killed when the tab is closed
  given owner: Owner = OneTimeOwner(() => ())

  // this should be a subset of activeSymbols' keys
  private val canvasSelectionV = Var(Set.empty[ViewerNodeId])

  val activeSymbolsV: Var[ActiveSymbols] =
    pageV.zoom(_.activeSymbols)((p, s) => p.copy(activeSymbols = s))

  val diagramOptionsV: Var[DiagramOptions] =
    pageV.zoom(_.diagramOptions)((p, s) => p.copy(diagramOptions = s))

  val canvasSelection =
    CanvasSelectionOps(canvasSelectionV)

  val activeSymbols =
    ActiveSymbolsOps(activeSymbolsV, graph, canvasSelectionV)

  val svgDiagram: Signal[SvgDiagram] =
    graph
      .combineWith(pageV.signal.distinct)
      .flatMapSwitch: (g, p) =>
        renderDot(toDot("", g.subgraph(p.activeSymbols.keySet)))

end ViewerState

class CanvasSelectionOps(
    canvasSelectionV: Var[Set[ViewerNodeId]] = Var(Set.empty)
):
  export canvasSelectionV.now
  val signal = canvasSelectionV.signal

  def toggle(s:  ViewerNodeId): Unit = canvasSelectionV.update(_.toggle(s))
  def replace(s: ViewerNodeId): Unit = canvasSelectionV.set(Set(s))
  def extend(s:  ViewerNodeId): Unit = canvasSelectionV.update(_ + s)

  def extend(ss: Set[ViewerNodeId]): Unit = canvasSelectionV.update(_ ++ ss)
  def remove(ss: Set[ViewerNodeId]): Unit = canvasSelectionV.update(_ -- ss)

  def clear(): Unit = canvasSelectionV.set(Set.empty)

  def selectParents(graph: ViewerGraph, svgDiagram: SvgDiagram, activeSymbols: ActiveSymbols): Unit =
    selectRelated(_.parentsOfAll(_), graph, svgDiagram, activeSymbols)

  def selectChildren(graph: ViewerGraph, svgDiagram: SvgDiagram, activeSymbols: ActiveSymbols): Unit =
    selectRelated(_.childrenOfAll(_), graph, svgDiagram, activeSymbols)

  private def selectRelated(
      selector:      (ViewerGraph, Set[ViewerNodeId]) => ViewerGraph,
      graph:         ViewerGraph,
      svgDiagram:    SvgDiagram,
      activeSymbols: ActiveSymbols
  ): Unit =
    val subGraph: ViewerGraph = graph.subgraph(activeSymbols.keySet)
    val selection = canvasSelectionV.now()
    val relatedDiagram: ViewerGraph = selector(subGraph, selection)
    val arrowSymbols = relatedDiagram.arrows.map((a, b) => ViewerNodeId(s"${b}_$a"))
    extend(relatedDiagram.nodeIds)
    extend(arrowSymbols)
    svgDiagram.select(relatedDiagram.nodeIds)
    svgDiagram.select(arrowSymbols)

end CanvasSelectionOps

class ActiveSymbolsOps(
    val activeSymbolsV:   Var[ActiveSymbols],
    val graph:            Signal[ViewerGraph],
    val canvasSelectionV: Var[Set[ViewerNodeId]]
):

  val signal = activeSymbolsV.signal

  def toggle(s: ViewerNodeId): Unit =
    activeSymbolsV.update: activeSymbols =>
      if activeSymbols.contains(s) then activeSymbols - s
      else activeSymbols + (s -> None)

  def extend(s: ViewerNodeId): Unit =
    activeSymbolsV.update(_ + (s -> None))

  def extend(ss: collection.Seq[ViewerNodeId]): Unit =
    activeSymbolsV.update(_ ++ ss.map(_ -> None))

  def clear(): Unit =
    activeSymbolsV.set(Map.empty)

  /** Updates (selected) active symbol's options based on the given function `f`
    */
  def updateSelectionOptions(f: SymbolOptions => SymbolOptions): Unit =
    val canvasSelection = canvasSelectionV.now()
    activeSymbolsV.update:
      _.transform: (sym, options) =>
        if canvasSelection.contains(sym) then Some(f(options.getOrElse(SymbolOptions())))
        else options

  /** Modify `activeSymbols` based on the given function `f`
    */
  def applyOnSelection[E <: dom.Event](
      f: (ActiveSymbols, Set[ViewerNodeId]) => ActiveSymbols
  )(ep: EventProp[E]) =
    ep.compose(_.sample(canvasSelectionV)) --> { selection =>
      activeSymbolsV.update(f(_, selection))
    }

  /** Adds all children of the canvas selection to activeSymbols
    */
  def addSelectionChildren[E <: dom.Event](ep: EventProp[E]) =
    addSelectionWith(_.childrenOf(_), ep)

  /** Adds all parents of the canvas selection to activeSymbols
    */
  def addSelectionParents[E <: dom.Event](ep: EventProp[E]) =
    addSelectionWith(_.parentsOf(_), ep)

  /** Updates activeSymbols with the given function `f` and the current canvas selection.
    */
  private def addSelectionWith[E <: dom.Event](
      f:  (ViewerGraph, ViewerNodeId) => ViewerGraph,
      ep: EventProp[E]
  ): Base =
    val combined = graph.combineWith(canvasSelectionV.signal)
    ep.compose(_.sample(combined)) --> { (diagram, selection) =>
      if selection.nonEmpty then
        val diagram1 = selection.foldLeft(ViewerGraph.empty)((acc, s) => f(diagram, s) ++ acc)
        extend(diagram1.nodeIds.toSeq)
    }
end ActiveSymbolsOps
