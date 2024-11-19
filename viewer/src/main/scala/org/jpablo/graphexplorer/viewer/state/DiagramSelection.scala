package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.findSelectableElement
import org.jpablo.graphexplorer.viewer.extensions.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Arrow, NodeId}
import org.scalajs.dom
import upickle.default.writeJs

import scala.scalajs.js.JSON

type SelectedNodes = Set[NodeId]

class DiagramSelectionOps:
  private val selectedNodes: Var[SelectedNodes] = Var(Set.empty)

  val signal = selectedNodes.signal
    .tapEach(s => if s.nonEmpty then dom.console.log("Selection: " + JSON.parse(writeJs(s).toString)))

  def now(): SelectedNodes = selectedNodes.now()

  def toggle(ss: NodeId*): Unit = selectedNodes.update(ss.foldLeft(_)(_.toggle(_)))

  def set(ss:    SelectedNodes): Unit = selectedNodes.set(ss)
  def add(ss:    SelectedNodes): Unit = selectedNodes.update(_ ++ ss)
  def remove(ss: SelectedNodes): Unit = selectedNodes.update(_ -- ss)

  def contains(s: NodeId): Boolean = selectedNodes.now().contains(s)

  def clear(): Unit = selectedNodes.set(Set.empty)

  val selectSuccessors = selectRelated(_.allSuccessorsGraph(_))
  val selectPredecessors = selectRelated(_.allPredecessorsGraph(_))
  val selectDirectSuccessors = selectRelated(_.directSuccessorsGraph(_))
  val selectDirectPredecessors = selectRelated(_.directPredecessorsGraph(_))

  private def selectRelated(
      selector: (ViewerGraph, SelectedNodes) => ViewerGraph
  )(fullGraph: ViewerGraph, hiddenNodes: HiddenNodes): Unit =
    val visibleSubGraph: ViewerGraph = fullGraph.removeNodes(hiddenNodes)
    val relatedSubGraph: ViewerGraph = selector(visibleSubGraph, selectedNodes.now())
    // Incorrect: relatedSubGraph.allArrowIds selects the wrong arrowIds
    val relatedIds = relatedSubGraph.allNodeIds ++ relatedSubGraph.allArrowIds
    add(relatedIds)

  def handleSvgClick(event: dom.MouseEvent): Unit =
    findSelectableElement(event) match
      case None                            => clear()
      case Some((nodeId: NodeId, metaKey)) => handleClickOnNode(nodeId)(metaKey)
      case Some((Some(arrow), metaKey))    => handleClickOnArrow(arrow)(metaKey)
      case _                               => ()

  def handleClickOnNode(nodeId: NodeId)(metaKey: Boolean) =
    if metaKey then
      toggle(nodeId)
    else
      set(Set(nodeId))

  def handleClickOnArrow(arrow: Arrow)(metaKey: Boolean) =
    val nodeId = arrow.nodeId
    if metaKey then
      toggle(nodeId)
    else
      set(Set(nodeId))

end DiagramSelectionOps
