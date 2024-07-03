package org.jpablo.typeexplorer.viewer.state

import com.raquo.airstream.core.{EventStream, Signal}
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.modifiers.Binder.Base
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph
import org.jpablo.typeexplorer.viewer.models.NodeId
import org.jpablo.typeexplorer.viewer.state.VisibleNodes
import org.scalajs.dom

/** The Ids of nodes displayed in the diagram
  */
type VisibleNodes = Map[NodeId, Option[NodeOptions]]

class VisibleNodesOps(
    val activeSymbolsV:   Var[VisibleNodes],
    val graph:            Signal[ViewerGraph],
    val canvasSelectionV: Var[Set[NodeId]]
):

  val signal = activeSymbolsV.signal

  def toggle(s: NodeId): Unit =
    activeSymbolsV.update: activeSymbols =>
      if activeSymbols.contains(s) then activeSymbols - s
      else activeSymbols + (s -> None)

  def extend(s: NodeId): Unit =
    activeSymbolsV.update(_ + (s -> None))

  def extend(ss: collection.Seq[NodeId]): Unit =
    activeSymbolsV.update(_ ++ ss.map(_ -> None))

  def clear(): Unit =
    activeSymbolsV.set(Map.empty)

  /** Updates (selected) active symbol's options based on the given function `f`
    */
  def updateSelectionOptions(f: NodeOptions => NodeOptions): Unit =
    val canvasSelection = canvasSelectionV.now()
    activeSymbolsV.update:
      _.transform: (sym, options) =>
        if canvasSelection.contains(sym) then Some(f(options.getOrElse(NodeOptions())))
        else options

  /** Modify `activeSymbols` based on the given function `f`
    */
  def applyOnSelection[E <: dom.Event](
      f: (VisibleNodes, Set[NodeId]) => VisibleNodes
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

  def addSelectionDirectParents[E <: dom.Event](ep: EventProp[E]) =
    addSelectionWith(_.directParentsOf(_), ep)

  private val graphWithSelection =
    graph.combineWith(canvasSelectionV.signal)

  /** Updates activeSymbols with the given function `f` and the current canvas selection.
    */
  private def addSelectionWith[E <: dom.Event](
      f:  (ViewerGraph, NodeId) => ViewerGraph,
      ep: EventProp[E]
  ): Base =
    ep.compose(_.sample(graphWithSelection)) --> { (diagram, selection) =>
      if selection.nonEmpty then
        val diagram1 = selection.foldLeft(ViewerGraph.empty)((acc, s) => f(diagram, s) ++ acc)
        extend(diagram1.nodeIds.toSeq)
    }
end VisibleNodesOps
