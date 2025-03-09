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
import org.jpablo.graphexplorer.viewer.utils.SvgPoint
import org.scalajs.dom.SVGRect
import upickle.default.*

import scala.util.Try

case class ViewerState(
    projectId:     ProjectId,
    writeText:     String => Any = _ => (),
    initialSource: String = ""
) extends TransformOps, DiagramSelectionOps, VisibilityOps, ExportOps, UIState, Persistence:
  given owner: Owner = OneTimeOwner(() => ())

  lazy val project =
    ProjectOps(Var(Project(projectId)))

  val sourceFlow = SourceFlow(initialSource, project.hiddenElements.signal, resetView)

  val undoEvent: EventBus[Unit] = EventBus()
  val redoEvent: EventBus[Unit] = EventBus()

  val sourceText = sourceFlow.sourceText

  val fullGraph = sourceFlow.fullGraph

  protected val visibleDOT = sourceFlow.visibleDOT

  val visibleGraph = sourceFlow.visibleGraph

  // 5. Render visible Dot to SVG
  // Dot ~> SVGSVGElement
  val rawSVG: Signal[dom.SVGSVGElement] =
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
      .zoomLazy(_.getAttributesById(elementIds))((graph, updates) => graph.updateAttributes(elementIds, updates))

  def getNodeById(ids: Seq[NodeId]): Seq[ViewerNode] =
    val g = fullGraph.observe().now()
    ids.flatMap(g.getNode)

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

end ViewerState

case class PersistedState(
    hiddenNodes:       HiddenElements = ElementIds(),
    projectName:       String = "",
    source:            String = "",
    rightPanelVisible: Boolean = true,
    sideBarTabIndex:   Int = 0,
    leftPanelVisible:  Boolean = true
) derives ReadWriter

object PersistedState:
  private val minimalGraphText = "digraph G {\n}"
  val empty =
    PersistedState(
      hiddenNodes       = ElementIds(),
      projectName       = "Untitled",
      source            = minimalGraphText,
      rightPanelVisible = true,
      sideBarTabIndex   = 0,
      leftPanelVisible  = true
    )

object ViewerState:

  def handleWheel(
      zoomValue:   Var[Double],
      translateXY: Var[SvgPoint]
  )(wEv: dom.WheelEvent, viewBox: SVGRect) =
    val clientHeight = dom.window.innerHeight max 1
    val clientWidth = dom.window.innerWidth max 1

    if wEv.metaKey && wEv.deltaY != 0 then
      zoomValue.update: z =>
        z - wEv.deltaY / clientHeight max 0.001
    else
      val z = zoomValue.now()
      val scale = viewBox.width / clientWidth max viewBox.height / clientHeight
      val delta = SvgPoint(wEv.deltaX * scale / z, wEv.deltaY * scale / z)
      translateXY.update(_ - delta)

end ViewerState
