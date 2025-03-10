package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.*
import org.jpablo.graphexplorer.viewer.components.svgCanvas.SvgCanvas
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.Rankdir
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.{ArrowId, AttributesUpdates, ElementIds, GroupId, NodeId, ViewerNode}
import upickle.default.*

import scala.util.Try

case class ViewerState(
    projectId:     ProjectId,
    writeText:     String => Any = _ => (),
    initialSource: String = ""
) extends SvgTransformOps, DiagramSelectionOps, VisibilityOps, ExportOps, UIState, Persistence:
  given owner: Owner = OneTimeOwner(() => ())

  lazy val project =
    ProjectOps(Var(Project(projectId)))

  protected[state] val sourceFlow = SourceFlow(initialSource, project.hiddenElements.signal, resetView)

  val undoEvent: EventBus[Unit] = EventBus()
  val redoEvent: EventBus[Unit] = EventBus()

  val sourceText = sourceFlow.sourceText

  val fullGraph = sourceFlow.fullGraph

  protected[state] val visibleDOT = sourceFlow.visibleDOT

  val visibleGraph = sourceFlow.visibleGraph

  // 5. Render visible Dot to SVG
  // Dot ~> SVGSVGElement
  private val rawSVG: Signal[dom.SVGSVGElement] =
    visibleDOT.flatMapSwitch(_.toSvg)

  // 6. SVG with extra elements: selection rect, etc.
  lazy val finalSVG: Signal[ReactiveSvgElement[dom.SVGSVGElement]] =
    rawSVG.map: svg =>
      def getRankdir =
        sourceFlow.fullGraphV.now().rootGroup.attributes
          .get(Rankdir.attrId)
          .map(_.value.toString)
          .map(str => Try(Rankdir.valueOf(str)).getOrElse(Rankdir.default))
          .getOrElse(Rankdir.default)

      SvgCanvas(svg, transform, this, addNode, () => getRankdir)

  // -------- storage ------------
  restoreState()

  def getNodeById(ids: Seq[NodeId]): Seq[ViewerNode] =
    ids.flatMap(fullGraph.observe().now().getNode)

  /** Adds a new node to the graph. If there is a currently selected node, the new node will be connected to it with an
    * edge. If the selected element is a group/cluster, the new node will be added to that group. The new node will
    * become the only selected element after creation.
    */
  def addNode() =
    sourceFlow.fullGraphV.update: fullGraph =>
      val s = selection.now()
      val (newGraph, newNodeId) =
        if s.isEmpty then
          fullGraph.addNode()
        else
          val source = s.head
          // Only proceed if selected ID is a valid node in the graph
          source match
            case id: NodeId  => fullGraph.addNodeAndArrowFrom(id)
            case id: GroupId => fullGraph.addNode(Some(id))
            case _: ArrowId  => fullGraph.addNode()
      selection.set(newNodeId)
      newGraph

  def addEdge(from: NodeId, to: NodeId): Unit =
    sourceFlow.fullGraphV.update: g =>
      val (g2, a) = g.addArrow(from, to)
      selection.set(ElementIds.from(a.id))
      g2

  // -------- Attribute management -----------

  // Optimization idea:
  // For changes that don't impact the layout we can update the SVG directly
  // instead of re-rendering the whole diagram

  // --- top level attributes ---
  def rootTargetAttributesUpdates(target: AttributeTarget): Var[AttributesUpdates] =
    sourceFlow.fullGraphV
      .zoomLazy(_.getRootAttributes(target).toUpdates): (graph, updates) =>
        graph.updateRootAttributes(target)(updates.applyUpdatesTo)

  // individual node attributes
  def elementAttributes(elementIds: ElementIds): Var[AttributesUpdates] =
    sourceFlow.fullGraphV
      .zoomLazy(_.getAttributesUpdatesById(elementIds))((graph, updates) => graph.updateAttributes(elementIds, updates))

end ViewerState
