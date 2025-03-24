package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.*
import org.jpablo.graphexplorer.viewer.components.svgCanvas.SvgCanvas
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{GraphType, Layout, Rankdir, Shape}
import org.jpablo.graphexplorer.viewer.graph.AttributesOps
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.zoomLens
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

      SvgCanvas(svg, transform, this, () => addNodeWithSmartConnection(), () => getRankdir)

  // -------- storage ------------
  restoreState()

  def nodeById(ids: Seq[NodeId]): Seq[ViewerNode] =
    ids.flatMap(fullGraph.observe().now().getNode)

  def allNodeIds(): Set[NodeId] =
    fullGraph.observe().now().nodeIds

  def allArrowIds(): Set[ArrowId] =
    fullGraph.observe().now().arrowIds

  /** Adds a new node to the graph. If there is a currently selected node, the new node will be connected to it with an
    * edge. If the selected element is a group/cluster, the new node will be added to that group. The new node will
    * become the only selected element after creation.
    *
    * @return
    *   The result of the operation, which can be:
    *   - None if no action was taken
    *   - Some(NodeAdded) if a standalone node was added
    *   - Some(NodeAndArrowAdded) if a node and an arrow were added
    */
  def addNodeWithSmartConnection(attributes: Attributes = Attributes.empty): Unit =
//    val shapeAttr = shape.fold(Map.empty)(s => Map(Shape.attrId -> AttrValue(s.toString)))

    sourceFlow.fullGraphV.update: fullGraph =>
      val sel = selection.now()

      if sel.isEmpty then
        val (newGraph, newNodeId) = fullGraph.addNode(attributes = attributes)
        selection.set(newNodeId)
        newGraph
      else
        val source = sel.head
        // Only proceed if selected ID is a valid node in the graph
        source match
          case id: NodeId =>
            val (newGraph, newNodeId, _) = fullGraph.addNodeAndArrowFrom(source = id, attributes = attributes)
            selection.set(newNodeId)
            newGraph
          case id: GroupId =>
            val (newGraph, newNodeId) = fullGraph.addNode(groupId = Some(id), attributes = attributes)
            selection.set(newNodeId)
            newGraph
          case _: ArrowId =>
            val (newGraph, newNodeId) = fullGraph.addNode(attributes = attributes)
            selection.set(newNodeId)
            newGraph

  def addArrow(from: NodeId, to: NodeId) =
    sourceFlow.fullGraphV.update: g =>
      val (g2, a) = g.addArrow(from, to)
      selection.set(ElementIds.from(a.id))
      g2

  // -------- Attribute management -----------

  // Optimization idea:
  // For changes that don't impact the layout we can update the SVG directly
  // instead of re-rendering the whole diagram

  // --- top level attributes ---

  def graphType: Var[GraphType] =
    sourceFlow.fullGraphV.zoomLazy(_.tpe)((g, tpe) => g.copy(tpe = tpe))

  def layout: Signal[Layout] =
    defaults(AttributeTarget.graph).map(_.getAs(Layout))

  def nodeShape: Signal[Shape] =
    defaults(AttributeTarget.node).map(_.getAs(Shape))

  /** This targets the root group of the graph. It is used to set attributes that apply to the entire graph, such as
    * background color, rank direction, etc.
    *
    * node [...]
    * edge [...]
    * graph [...]
    */
  def rootTargetAttributesUpdates(target: AttributeTarget): Var[AttributesUpdates] =
    sourceFlow.fullGraphV.zoomLens(AttributesOps.rootAttributesUpdates(target))

  def defaults(target: AttributeTarget): Signal[Attributes] =
    fullGraph.map(_.getRootAttributes(target))

  // individual node attributes
  def elementAttributes(elementIds: ElementIds): Var[AttributesUpdates] =
    sourceFlow.fullGraphV.zoomLens(AttributesOps.elementAttributesUpdates(elementIds))

end ViewerState
