package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.components.*
import org.jpablo.graphexplorer.viewer.components.svgCanvas.SvgCanvas
import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{GraphType, Label, Layout, Rankdir, Shape}
import org.jpablo.graphexplorer.viewer.graph.AttributesOps
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.zoomLens
import upickle.default.*

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
      SvgCanvas(
        rawSvg = svg,
        transform = transform,
        selectionOps = this,
        addNode = () => addNodeWithSmartConnection(),
        getRankdir = () => graphRankDir.observe().now(),
        updateLabel = updateLabel
      )

  // -------- storage ------------
  restoreState()

  def nodeById(ids: Seq[NodeId]): Seq[ViewerNode] =
    ids.flatMap(fullGraph.observe().now().getNode)

  def allNodeIds(): Set[NodeId] =
    fullGraph.observe().now().nodeIds

  def allArrowIds(): Set[ArrowId] =
    fullGraph.observe().now().arrowIds

  enum Direction derives CanEqual:
    case From, To

  /** Adds a new node to the graph. If there is a currently selected node, the new node will be connected to it with an edge. If the
    * selected element is a group/cluster, the new node will be added to that group. The new node will become the only selected element
    * after creation.
    *
    * @param attributes
    *   The attributes to apply to the new node
    * @param direction
    *   The direction of the arrow when connecting to an existing node (From: existing -> new, To: new -> existing)
    * @return
    *   The result of the operation, which can be:
    *   - None if no action was taken
    *   - Some(NodeAdded) if a standalone node was added
    *   - Some(NodeAndArrowAdded) if a node and an arrow were added
    */
  def addNodeWithSmartConnection(
      attributes: Attributes = Attributes.empty,
      direction:  Direction = Direction.From
  ): Unit =
    sourceFlow.fullGraphV.update: fullGraph =>
      val sel = selection.now()

      if sel.isEmpty then
        val (newGraph, newNodeId) = fullGraph.addNode(attributes = attributes)
        selection.set(newNodeId)
        newGraph
      else
        val selected = sel.head
        // Only proceed if selected ID is a valid node in the graph
        selected match
          case id: NodeId =>
            val (newGraph, _, _) = direction match
              case Direction.From => fullGraph.addNodeAndArrowFrom(source = id, attributes = attributes)
              case Direction.To   => fullGraph.addNodeAndArrowTo(target = id, attributes = attributes)
            newGraph
          case id: GroupId =>
            val (newGraph, _) = fullGraph.addNode(groupId = Some(id), attributes = attributes)
            newGraph
          case _: ArrowId =>
            val (newGraph, _) = fullGraph.addNode(attributes = attributes)
            newGraph

  def editLabel(): Unit =
    selection.editSelectedLabel()

  def addArrow(from: NodeId, to: NodeId) =
    sourceFlow.fullGraphV.update: g =>
      val (g2, _) = g.addArrow(from, to)
      selection.set(ElementIds.from(from))
      g2

  // -------- Attribute management -----------

  // Optimization idea:
  // For changes that don't impact the layout we can update the SVG directly
  // instead of re-rendering the whole diagram

  // --- top level attributes ---

  def graphType: Var[GraphType] =
    sourceFlow.fullGraphV.zoomLazy(_.tpe)((g, tpe) => g.copy(tpe = tpe))

  def graphLayout: Signal[Layout] =
    graphAttributes.map(_.getAs(Layout))

  def graphRankDir: Signal[Rankdir] =
    graphAttributes.map(_.getAs(Rankdir))

  def graphAttributes: Signal[Attributes] =
    fullGraph.map(_.elements.graphAttributes)

  def nodeShape: Signal[Shape] =
    defaults(AttributeTarget.node).map(_.getAs(Shape))

  def updateLabel(elementId: ElementId, label: String): Unit =
    elementAttributesUpdates(ElementIds.from(elementId)).set:
      AttributesUpdates.of(Label -> TextUtils.escape(label))

  def defaults(target: AttributeTarget): Signal[Attributes] =
    fullGraph.map(_.getDefaultAttributes(target))

  def diagramAttributesUpdates: Var[AttributesUpdates] =
    sourceFlow.fullGraphV.zoomLens(AttributesOps.diagramAttributesUpdates)

  def defaultAttributesUpdates(target: AttributeTarget): Var[AttributesUpdates] =
    sourceFlow.fullGraphV.zoomLens(AttributesOps.defaultAttributesUpdates(target))

  def elementAttributesUpdates(elementIds: ElementIds): Var[AttributesUpdates] =
    sourceFlow.fullGraphV.zoomLens(AttributesOps.elementAttributesUpdates(elementIds))

end ViewerState
