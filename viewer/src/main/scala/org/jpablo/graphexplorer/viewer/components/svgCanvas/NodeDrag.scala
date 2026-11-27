package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.gxcore.layout.BendPath.{NodeShape, Vec}
import org.jpablo.graphexplorer.gxcore.model.LayoutPoint
import org.jpablo.graphexplorer.viewer.backends.graphviz.BendRender
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.ArrowPosition
import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.models.{ArrowId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.ManualLayoutOps
import org.jpablo.graphexplorer.viewer.state.ManualLayoutOps.NodeDragPlan
import org.scalajs.dom

import scala.scalajs.js

/** The look of a drag of nodes on the canvas (manual layout, ADR 0004).
  *
  * A drag does not render the diagram again for each move of the pointer. It writes a `translate` on the group of
  * each moved node, directly in the SVG. The release stores one move in the layout, and the next render routes the
  * arrows again.
  *
  * During the drag:
  *   - An arrow between two moved nodes moves with them: it gets the same `translate`.
  *   - An arrow with one moved end goes away, and a stand-in shows in its place. The stand-in is a copy of the arrow
  *     with a different style: it has no id, no hit area, no label, and it takes no pointer events. Each move routes
  *     the stand-in again with [[BendRender.reroute]], from the border of the source to the border of the target.
  *     The shape of the moved node moves by the delta of the drag. The path goes through the stored bends of the
  *     arrow: when a move takes one end only, the bends stay. With no bends, the path is straight. The head turns
  *     to the new direction, and its tip is on the border of the target. The next render gives the true route.
  *     When the canvas cannot route a copy (the arrow has no Graphviz path), the arrow goes away with no stand-in.
  *   - Without Alt, the canvas shows the snap grid under the drawing, in layout points.
  *
  * The SVG of a free layout comes from Graphviz: the nodes and the arrows are children of the root group, and a
  * place `(x, y)` of the drawing is the point `(x, -y)` of the SVG.
  */
final class NodeDrag private (
    plan:      NodeDragPlan,
    mainGroup: dom.svg.G,
    /** The node groups and the arrows that move as one. */
    rigid: Seq[dom.Element],
    /** Each arrow with one moved end, and its stand-in, if the canvas can route one. */
    stretched: Seq[(dom.Element, Option[NodeDrag.StandIn])],
    /** Client pixels for one SVG unit, on each axis. */
    pxX: Double,
    pxY: Double
):
  private val originalTransforms = rigid.map(e => e -> Option(e.getAttribute("transform")))
  private var grid               = Option.empty[dom.Element]
  private var current            = (0.0, 0.0)

  /** The move of the drag in layout points (the y axis goes up). */
  def delta: (Double, Double) = current

  /** The nodes that the drag moves. */
  def nodes: Set[NodeId] = plan.nodes

  /** Follow the pointer: the client move `(dx, dy)` from the press. Without `alt`, the move snaps to the grid. */
  def update(clientDx: Double, clientDy: Double, alt: Boolean): Unit =
    val raw = (clientDx / pxX, -clientDy / pxY)
    current = ManualLayoutOps.dragDelta(plan.from, raw._1, raw._2, snap = !alt)
    val (sx, sy) = (current._1, -current._2)
    for (e, original) <- originalTransforms do
      e.setAttribute("transform", s"translate(${fmt(sx)} ${fmt(sy)})" + original.fold("")(" " + _))
    for (_, standIn) <- stretched; s <- standIn do s.route(Vec(sx, sy))
    showGrid(!alt)

  /** Put back the drawing as it was before the drag. */
  def cancel(): Unit =
    for (e, original) <- originalTransforms do
      original match
        case Some(t) => e.setAttribute("transform", t)
        case None    => e.removeAttribute("transform")
    for (edge, standIn) <- stretched do
      edge.asInstanceOf[dom.html.Element].style.removeProperty("visibility")
      standIn.foreach(_.element.remove())
    showGrid(false)
    mainGroup.ownerSVGElement.classList.remove(NodeDrag.DraggingClass)

  /** The release stored the move: keep the nodes at their new places until the new render replaces this SVG. The
    * grid goes away.
    */
  def hold(): Unit =
    showGrid(false)
    mainGroup.ownerSVGElement.classList.remove(NodeDrag.DraggingClass)

  private def showGrid(on: Boolean): Unit =
    (on, grid) match
      case (true, None) =>
        val layer = NodeDrag.gridLayer(mainGroup, plan.offset, pxX)
        grid = Some(layer)
      case (false, Some(layer)) =>
        layer.remove()
        grid = None
      case _ => ()

  private def fmt(v: Double): String = (math.round(v * 1000).toDouble / 1000).toString

end NodeDrag

object NodeDrag:

  private val SvgNs = "http://www.w3.org/2000/svg"

  /** On the svg while nodes follow the pointer (see style.scss). */
  val DraggingClass = "gx-node-dragging"

  /** The class of the stand-in of an arrow with one moved end. */
  val LinkClass = "gx-drag-link"

  /** The class of the layer of the snap grid. */
  val GridClass = "gx-snap-grid"

  /** The stand-in of an arrow with one moved end: a copy of the arrow, routed again at each move.
    *
    * @param element   the copy, in the SVG after the arrow
    * @param start     the position of the copy at the start (the position of the arrow)
    * @param fixed     the shape of the end that stays, in SVG units
    * @param moving    the shape of the end that moves, at the start
    * @param sourceMoves true when the moved node is the source of the arrow
    * @param bends     the stored bends of the arrow, in SVG units
    */
  final class StandIn(
      val element: dom.Element,
      start:       ArrowPosition,
      fixed:       NodeShape,
      moving:      NodeShape,
      sourceMoves: Boolean,
      bends:       Vector[Vec]
  ):
    private var position = start

    /** Route the copy for the move `d` of the moved node (SVG units). False when the copy has no Graphviz path. */
    def route(d: Vec): Boolean =
      val moved = shifted(moving, d)
      val (source, target) = if sourceMoves then (moved, fixed) else (fixed, moved)
      BendRender.reroute(element, position, source, target, bends) match
        case Some(next) =>
          position = next
          true
        case None => false

  private def shifted(shape: NodeShape, d: Vec): NodeShape =
    shape match
      case NodeShape.Ellipse(c, rx, ry) => NodeShape.Ellipse(c + d, rx, ry)
      case NodeShape.Polygon(ps)        => NodeShape.Polygon(ps.map(_ + d))

  /** A copy of the arrow `edge` for a stand-in: with no ids, no hit area, no labels and no tooltip, so that the
    * selection and the hit tests do not see it. The copy goes after the arrow.
    */
  private def copyOf(edge: dom.Element): dom.Element =
    val copy = edge.cloneNode(true).asInstanceOf[dom.Element]
    copy.querySelectorAll(s".${SelectableElement.hitAreaClass}, text, title, foreignObject").foreach(_.remove())
    (copy +: copy.querySelectorAll("[id]").toSeq.collect { case e: dom.Element => e }).foreach(_.removeAttribute("id"))
    copy.setAttribute("class", LinkClass)
    copy.setAttribute("pointer-events", "none")
    edge.parentNode.insertBefore(copy, edge.nextSibling)
    copy

  /** Start the look of a drag. `arrows` gives the visible arrows with their two ends. `positions` gives the position
    * of each arrow in the SVG, by the value of its id. `shapes` gives the shape of a node in the SVG, and `bends` the
    * stored bends of an arrow, in layout points. None when the SVG has no group for the pressed node.
    */
  def begin(
      svg:       dom.svg.SVG,
      mainGroup: dom.svg.G,
      strategy:  SelectableElementStrategy,
      plan:      NodeDragPlan,
      arrows:    Iterable[(ArrowId, NodeId, NodeId)],
      positions: Map[String, ArrowPosition],
      shapes:    NodeId => Option[NodeShape],
      bends:     ArrowId => Vector[LayoutPoint]
  ): Option[NodeDrag] =
    val groups = SelectableElement.query(svg, ElementIds(plan.nodes), strategy).flatMap(e => e.nodeId.map(_ -> e.ref)).toMap
    for
      pressed <- groups.get(plan.pressed)
      ctm     <- Option(pressed.asInstanceOf[js.Dynamic].getScreenCTM().asInstanceOf[dom.SVGMatrix])
      if ctm.a > 0 && ctm.d > 0
    yield
      val touched     = arrows.filter((_, s, t) => plan.nodes.contains(s) || plan.nodes.contains(t)).toVector
      val edgeOf      = SelectableElement.query(svg, ElementIds(touched.map(_._1).toSet), strategy).flatMap(e => e.arrowId.map(_ -> e.ref)).toMap
      val (both, one) = touched.partition((_, s, t) => plan.nodes.contains(s) && plan.nodes.contains(t))
      val stretched =
        one.flatMap: (id, s, t) =>
          edgeOf.get(id).map: edge =>
            val sourceMoves = plan.nodes.contains(s)
            val (fixedId, movingId) = if sourceMoves then (t, s) else (s, t)
            val standIn =
              for
                position <- positions.get(id.value)
                fixed    <- shapes(fixedId)
                moving   <- shapes(movingId)
              yield
                val copy = copyOf(edge)
                StandIn(copy, BendRender.drawnTips(copy, position), fixed, moving, sourceMoves, bends(id).map(BendRender.toSvg(_, plan.offset)))
            // A copy with no Graphviz path cannot follow the node: it goes away.
            val routed = standIn.filter: s =>
              val ok = s.route(Vec(0, 0))
              if !ok then s.element.remove()
              ok
            edge.asInstanceOf[dom.html.Element].style.setProperty("visibility", "hidden")
            (edge, routed)
      svg.classList.add(DraggingClass)
      NodeDrag(plan, mainGroup, groups.values.toSeq ++ both.flatMap((id, _, _) => edgeOf.get(id)), stretched, ctm.a, ctm.d)

  /** The snap grid under the drawing: a dot at each point of the grid of the layout.
    *
    * A place `(x, y)` of the layout is the point `(x - ox, oy - y)` of the SVG, for the offset `(ox, oy)` of the
    * layout. The layer goes after the background of the graph and before the first element, so that the background
    * does not hide it.
    */
  private def gridLayer(mainGroup: dom.svg.G, offset: (Double, Double), pxPerUnit: Double): dom.Element =
    val g       = ManualLayoutOps.GridPt
    val (ox, oy) = offset
    val layer   = dom.document.createElementNS(SvgNs, "g")
    layer.setAttribute("class", GridClass)
    layer.setAttribute("pointer-events", "none")
    val defs    = dom.document.createElementNS(SvgNs, "defs")
    val pattern = dom.document.createElementNS(SvgNs, "pattern")
    val patternId = "gx-snap-grid-pattern"
    pattern.setAttribute("id", patternId)
    pattern.setAttribute("patternUnits", "userSpaceOnUse")
    pattern.setAttribute("width", g.toString)
    pattern.setAttribute("height", g.toString)
    // The dot is at the center of a tile, so the tile starts half a step before a point of the grid.
    pattern.setAttribute("x", (-ox - g / 2).toString)
    pattern.setAttribute("y", (oy - g / 2).toString)
    val dot = dom.document.createElementNS(SvgNs, "circle")
    dot.setAttribute("cx", (g / 2).toString)
    dot.setAttribute("cy", (g / 2).toString)
    // About one client pixel at the zoom of the start.
    dot.setAttribute("r", (1.1 / pxPerUnit).min(g / 4).toString)
    pattern.appendChild(dot)
    defs.appendChild(pattern)
    layer.appendChild(defs)
    val box  = mainGroup.getBBox()
    val rect = dom.document.createElementNS(SvgNs, "rect")
    val margin = 4000.0
    rect.setAttribute("x", (box.x - margin).toString)
    rect.setAttribute("y", (box.y - margin).toString)
    rect.setAttribute("width", (box.width + 2 * margin).toString)
    rect.setAttribute("height", (box.height + 2 * margin).toString)
    rect.setAttribute("fill", s"url(#$patternId)")
    layer.appendChild(rect)
    val firstElement = (0 until mainGroup.children.length).map(mainGroup.children.item).find(_.tagName.equalsIgnoreCase("g"))
    mainGroup.insertBefore(layer, firstElement.orNull)
    layer

end NodeDrag
