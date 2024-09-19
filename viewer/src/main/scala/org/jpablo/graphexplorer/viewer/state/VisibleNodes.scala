package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.{EventStream, Signal}
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.modifiers.Binder.Base
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.scalajs.dom

/** The Ids of nodes displayed in the diagram
  */
type VisibleNodes = Map[NodeId, Option[NodeOptions]]

class VisibleNodesOps(
    val visibleNodesV:    Var[VisibleNodes],
    val graph:            Signal[ViewerGraph],
    val canvasSelectionV: Var[Set[NodeId]]
):

  val signal = visibleNodesV.signal

  def toggle(s: NodeId): Unit =
    visibleNodesV.update: visibleNodes =>
      if visibleNodes.contains(s) then visibleNodes - s
      else visibleNodes + (s -> None)

  def extend(s: NodeId): Unit =
    visibleNodesV.update(_ + (s -> None))

  def extend(ss: collection.Seq[NodeId]): Unit =
    visibleNodesV.update(_ ++ ss.map(_ -> None))

  def clear(): Unit =
    visibleNodesV.set(Map.empty)

  /** Updates (selected) active symbol's options based on the given function `f`
    */
  def updateSelectionOptions(f: NodeOptions => NodeOptions): Unit =
    val canvasSelection = canvasSelectionV.now()
    visibleNodesV.update:
      _.transform: (sym, options) =>
        if canvasSelection.contains(sym) then Some(f(options.getOrElse(NodeOptions())))
        else options

  /** Modify `activeSymbols` based on the given function `f`
    */
  def applyOnSelection[E <: dom.Event](
      f: (VisibleNodes, Set[NodeId]) => VisibleNodes
  )(ep: EventProp[E]) =
    ep.compose(_.sample(canvasSelectionV)) --> { selection =>
      visibleNodesV.update(f(_, selection))
    }

  /** Updates activeSymbols with the given function `f` and the current canvas selection.
    */
  def addSelectionWith[E <: dom.Event](ep: EventProp[E])(f: (ViewerGraph, NodeId) => ViewerGraph): Base =
    ep.compose(_.sample(graph.combineWith(canvasSelectionV.signal))) --> { (graph, selection) =>
      if selection.nonEmpty then
        val diagram1 = selection.foldLeft(ViewerGraph.empty)((acc, s) => f(graph, s) ++ acc)
        extend(diagram1.nodeIds.toSeq)
    }
end VisibleNodesOps
