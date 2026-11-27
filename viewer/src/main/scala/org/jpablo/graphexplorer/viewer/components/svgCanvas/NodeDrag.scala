package org.jpablo.graphexplorer.viewer.components.svgCanvas

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
  *   - An arrow with one moved end goes away, and a straight dashed line joins the centers of its two nodes. The
  *     spline of Graphviz has no meaning for the new place, and the next render gives the true route.
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
    /** Each arrow with one moved end: the arrow, its line, the fixed center, and the moving center (SVG units). */
    stretched: Seq[(dom.Element, dom.Element, (Double, Double), (Double, Double))],
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
    for (_, line, fixed, moving) <- stretched do
      line.setAttribute("x1", fmt(fixed._1))
      line.setAttribute("y1", fmt(fixed._2))
      line.setAttribute("x2", fmt(moving._1 + sx))
      line.setAttribute("y2", fmt(moving._2 + sy))
    showGrid(!alt)

  /** Put back the drawing as it was before the drag. */
  def cancel(): Unit =
    for (e, original) <- originalTransforms do
      original match
        case Some(t) => e.setAttribute("transform", t)
        case None    => e.removeAttribute("transform")
    for (edge, line, _, _) <- stretched do
      edge.asInstanceOf[dom.html.Element].style.removeProperty("visibility")
      line.remove()
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

  /** The class of the straight line of an arrow with one moved end. */
  val LinkClass = "gx-drag-link"

  /** The class of the layer of the snap grid. */
  val GridClass = "gx-snap-grid"

  /** Start the look of a drag. `arrows` gives the visible arrows with their two ends. None when the SVG has no group
    * for the pressed node.
    */
  def begin(
      svg:       dom.svg.SVG,
      mainGroup: dom.svg.G,
      strategy:  SelectableElementStrategy,
      plan:      NodeDragPlan,
      arrows:    Iterable[(ArrowId, NodeId, NodeId)]
  ): Option[NodeDrag] =
    val groups = SelectableElement.query(svg, ElementIds(plan.nodes), strategy).flatMap(e => e.nodeId.map(_ -> e.ref)).toMap
    for
      pressed <- groups.get(plan.pressed)
      ctm     <- Option(pressed.asInstanceOf[js.Dynamic].getScreenCTM().asInstanceOf[dom.SVGMatrix])
      if ctm.a > 0 && ctm.d > 0
    yield
      val touched   = arrows.filter((_, s, t) => plan.nodes.contains(s) || plan.nodes.contains(t)).toVector
      val edgeOf    = SelectableElement.query(svg, ElementIds(touched.map(_._1).toSet), strategy).flatMap(e => e.arrowId.map(_ -> e.ref)).toMap
      val (both, one) = touched.partition((_, s, t) => plan.nodes.contains(s) && plan.nodes.contains(t))
      val ends      = SelectableElement.query(svg, ElementIds(one.flatMap((_, s, t) => Seq(s, t)).toSet), strategy).flatMap(e => e.nodeId.map(_ -> e.ref)).toMap
      val toLocal   = Option(mainGroup.getScreenCTM()).map(_.inverse())
      val firstNode = (0 until mainGroup.children.length).map(mainGroup.children.item).find(_.matches(strategy.nodeSelector))
      def centerOf(e: dom.Element): Option[(Double, Double)] =
        toLocal.map: m =>
          val r  = e.getBoundingClientRect()
          val pt = svg.createSVGPoint()
          pt.x = r.left + r.width / 2
          pt.y = r.top + r.height / 2
          val local = pt.matrixTransform(m)
          (local.x, local.y)
      val stretched =
        one.flatMap: (id, s, t) =>
          val (fixedId, movingId) = if plan.nodes.contains(s) then (t, s) else (s, t)
          for
            edge   <- edgeOf.get(id)
            fixed  <- ends.get(fixedId).flatMap(centerOf)
            moving <- ends.get(movingId).flatMap(centerOf)
          yield
            val line = dom.document.createElementNS(SvgNs, "line")
            line.setAttribute("class", LinkClass)
            line.setAttribute("x1", fixed._1.toString)
            line.setAttribute("y1", fixed._2.toString)
            line.setAttribute("x2", moving._1.toString)
            line.setAttribute("y2", moving._2.toString)
            edge.asInstanceOf[dom.html.Element].style.setProperty("visibility", "hidden")
            // Under the nodes: the fill of a node covers the end of the line at its center.
            mainGroup.insertBefore(line, firstNode.orNull)
            (edge, line, fixed, moving)
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
