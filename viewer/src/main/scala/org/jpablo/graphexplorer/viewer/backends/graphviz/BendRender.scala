package org.jpablo.graphexplorer.viewer.backends.graphviz

import org.jpablo.graphexplorer.gxcore.layout.BendPath
import org.jpablo.graphexplorer.gxcore.layout.BendPath.{ArrowEnd, NodeShape, Vec}
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, LayoutPoint}
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{ArrowPosition, Point}
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{ArrowId, NodeId}
import org.scalajs.dom

/** The arrows with bend points in the SVG of a free layout (ADR 0004, part 4).
  *
  * neato honours the `pos` of an arrow only in its `-n2` mode, and viz-js does not give that mode. Thus neato routes
  * each arrow, and this object draws the path of an arrow with bend points again, in the SVG:
  *   - The path is a smooth curve through the bends ([[BendPath.route]]). It goes from the boundary of the source node
  *     to the base of the arrowhead. The boundary comes from the shape of the node in the SVG: an ellipse, a polygon,
  *     or the hull of a path.
  *   - The arrowhead (and the tail decoration) turns and moves to the new end. Its shape stays.
  *   - A label moves with the part of the arrow that it is nearest to: the start, the middle, or the end.
  *
  * The canvas uses the same function for the look of a drag of a bend ([[reroute]]), so the drag shows the final
  * path with no new render.
  *
  * The SVG of Graphviz has the place `(x, y)` of the drawing at the point `(x, -y)`. A place of the layout is a place
  * of the drawing plus the offset of a pinned node.
  */
object BendRender:

  /** Draw each arrow of `bends` through its bend points, in the SVG of `drawn`. The positions of these arrows change
    * to the new ends, so that the end controls of a selected arrow are at the new ends.
    */
  def applyTo(
      drawn:  SvgWithPositions,
      graph:  ViewerGraph,
      layout: DiagramLayout,
      bends:  Map[ArrowId, Vector[LayoutPoint]]
  ): SvgWithPositions =
    if bends.isEmpty then drawn
    else
      offset(drawn.nodePositions, layout).fold(drawn): off =>
        val svg   = drawn.svg.ref
        val edges = edgesById(svg)
        val shape = nodeShapes(svg, drawn.nodePositions, drawn.nodeSizes)
        val rerouted =
          for
            (id, points) <- bends
            arrow        <- graph.arrows.get(id)
            edge         <- edges.get(id)
            position     <- drawn.edgePositions.get(id.value)
            source       <- shape(arrow.source)
            target       <- shape(arrow.target)
            next         <- reroute(edge, position, source, target, points.map(toSvg(_, off)))
          yield id.value -> next
        drawn.copy(edgePositions = drawn.edgePositions ++ rerouted)

  /** The offset from the frame of the drawing to the frame of the layout: a place of the layout is a place of the
    * drawing plus this offset. A pinned node gives it. With no pinned node, there is none.
    */
  def offset(nodePositions: Map[String, Point], layout: DiagramLayout): Option[(Double, Double)] =
    nodePositions.iterator.flatMap((id, p) => layout.point(id).map(s => (s.x - p.x, s.y - p.y))).nextOption()

  /** The point of the SVG for the layout point `p`, with the offset `off` of the layout. */
  def toSvg(p: LayoutPoint, off: (Double, Double)): Vec = Vec(p.x - off._1, -(p.y - off._2))

  /** The arrows of the SVG, by their ids. */
  def edgesById(root: dom.Element): Map[ArrowId, dom.Element] =
    root.querySelectorAll("g.edge").toSeq.collect { case e: dom.Element => e }.flatMap(e => ArrowId.fromSvg(e.id).map(_ -> e)).toMap

  /** The nodes of the SVG, by their ids. */
  def nodesById(root: dom.Element): Map[NodeId, dom.Element] =
    root.querySelectorAll("g.node").toSeq.collect { case e: dom.Element => e }.flatMap(e => NodeId.fromSvg(e.id).map(_ -> e)).toMap

  /** The shape of each node of `root`, by its id, for the ends of an arrow with bends. A node with no shape in the
    * SVG (the shape `none` or `plain`) uses its box from Graphviz: `nodePositions` (the y axis up) and `nodeSizes`.
    * The render and the drag of a bend use this same function.
    */
  def nodeShapes(root: dom.Element, nodePositions: Map[String, Point], nodeSizes: Map[String, (Double, Double)]): NodeId => Option[NodeShape] =
    val nodes = nodesById(root)
    id =>
      nodes.get(id).flatMap: g =>
        shapeOf(g).orElse:
          for
            p      <- nodePositions.get(id.value)
            (w, h) <- nodeSizes.get(id.value)
          yield NodeShape.box(Vec(p.x, -p.y), w, h)

  /** The shape of a Graphviz node: its largest ellipse, polygon or path. A node with more than one periphery, or a
    * record with cells, has more than one. Graphviz draws some shapes as a path, for example a rounded box or a
    * cylinder: the shape is then the hull of the points of the path. None for a node with no such element (the shape
    * `none` or `plain`).
    */
  def shapeOf(node: dom.Element): Option[NodeShape] =
    val shapes =
      node.querySelectorAll("ellipse, polygon, path").toSeq.collect { case e: dom.Element => e }.flatMap: e =>
        if e.tagName.equalsIgnoreCase("path") then NodeShape.ofPath(e.getAttribute("d"))
        else if e.tagName.equalsIgnoreCase("ellipse") then
          for
            cx <- num(e, "cx")
            cy <- num(e, "cy")
            rx <- num(e, "rx")
            ry <- num(e, "ry")
            if rx > 0 && ry > 0
          yield NodeShape.Ellipse(Vec(cx, cy), rx, ry)
        else
          val points = pointsOf(e.getAttribute("points"))
          Option.when(points.size >= 3)(NodeShape.Polygon(points))
    shapes.maxByOption(area).filter(area(_) > 0)

  private def area(shape: NodeShape): Double =
    shape match
      case NodeShape.Ellipse(_, rx, ry) => 4 * rx * ry
      case NodeShape.Polygon(ps)        => (ps.map(_.x).max - ps.map(_.x).min) * (ps.map(_.y).max - ps.map(_.y).min)

  /** Draw the arrow `edge` again: from `source` through `bends` (points of the SVG) to `target`.
    *
    * `current` is the position of the arrow as the SVG draws it now: its end tips, in the frame of the drawing. The
    * decorations turn from these tips to the new tips. The result is the new position, in the same frame. None when
    * the path of the arrow is not a Graphviz path.
    */
  def reroute(edge: dom.Element, current: ArrowPosition, source: NodeShape, target: NodeShape, bends: Vector[Vec]): Option[ArrowPosition] =
    val paths   = edge.querySelectorAll("path").toSeq.collect { case e: dom.Element => e }
    val halos   = paths.filter(_.classList.contains(SelectableElement.hitAreaClass))
    val splines = paths.find(p => !p.classList.contains(SelectableElement.hitAreaClass)).toSeq
    val old     = splines.headOption.map(p => BendPath.parsePath(p.getAttribute("d"))).getOrElse(Vector.empty)
    Option.when(old.nonEmpty):
      val headTip = Vec(current.endPoint.x, -current.endPoint.y)
      val tailTip = Vec(current.startPoint.x, -current.startPoint.y)
      val (oldStart, oldEnd) = (old.head.p0, old.last.p1)
      def decoration(tip: Vec, base: Vec): Double =
        val l = (tip - base).length
        if l > 0.5 then l else 0.0
      val headLength = decoration(headTip, oldEnd)
      val tailLength = decoration(tailTip, oldStart)
      val route      = BendPath.route(source, target, bends, headLength, tailLength)
      val d          = BendPath.pathData(route.segments)
      (splines ++ halos).foreach(_.setAttribute("d", d))
      // The decorations: each other shape of the arrow belongs to the nearest end with a decoration.
      val oldHead  = ArrowEnd(headTip, (headTip - oldEnd).unit.getOrElse(route.head.direction))
      val oldTail  = ArrowEnd(tailTip, (tailTip - oldStart).unit.getOrElse(route.tail.direction))
      val moveHead = BendPath.endMove(oldHead, route.head)
      val moveTail = BendPath.endMove(oldTail, route.tail)
      val keep     = (splines ++ halos).toSet
      edge.querySelectorAll("polygon, polyline, ellipse, path").toSeq.collect { case e: dom.Element if !keep.contains(e) => e }.foreach: e =>
        centerOf(e).foreach: c =>
          val toHead = (c - headTip).length
          val toTail = (c - tailTip).length
          val move =
            if headLength > 0 && (tailLength == 0 || toHead <= toTail) then Some(moveHead)
            else if tailLength > 0 then Some(moveTail)
            else None
          move.foreach(mapShape(e, _))
      // The labels: a label moves with its nearest anchor.
      val oldMid  = BendPath.pointAtHalf(old)
      val newMid  = BendPath.pointAtHalf(route.segments)
      val anchors = Vector(tailTip -> route.tail.tip, oldMid -> newMid, headTip -> route.head.tip)
      edge.querySelectorAll("text").toSeq.collect { case e: dom.Element => e }.foreach: t =>
        for
          x <- num(t, "x")
          y <- num(t, "y")
        do
          val p          = Vec(x, y)
          val (from, to) = anchors.minBy((a, _) => (p - a).length)
          val moved      = p + (to - from)
          t.setAttribute("x", fmt(moved.x))
          t.setAttribute("y", fmt(moved.y))
      def drawing(v: Vec) = Point(v.x, -v.y)
      val controls = route.segments.headOption.map(_.p0).toList ++ route.segments.flatMap(s => List(s.c1, s.c2, s.p1))
      ArrowPosition(drawing(route.tail.tip), drawing(route.head.tip), controls.map(drawing))

  /** The position `current` of the arrow `edge`, with each end at the drawn tip of its decoration.
    *
    * Graphviz stops an arrowhead a small distance before the end of the arrow, and the stroke of the head covers the
    * gap. A copy of the arrow with a thinner stroke shows the gap. Thus the tip here is the point of the decoration
    * that is farthest along the end of the path. An end with no decoration stays.
    */
  def drawnTips(edge: dom.Element, current: ArrowPosition): ArrowPosition =
    val spline = edge.querySelectorAll("path").toSeq.collect { case e: dom.Element => e }.find(p => !p.classList.contains(SelectableElement.hitAreaClass))
    val old    = spline.map(p => BendPath.parsePath(p.getAttribute("d"))).getOrElse(Vector.empty)
    if old.isEmpty then current
    else
      val keep    = spline.toSet
      val headTip = Vec(current.endPoint.x, -current.endPoint.y)
      val tailTip = Vec(current.startPoint.x, -current.startPoint.y)
      val points =
        edge.querySelectorAll("polygon, polyline, ellipse, path").toSeq.collect:
          case e: dom.Element if !keep.contains(e) && !e.classList.contains(SelectableElement.hitAreaClass) => e
        .flatMap: e =>
          if e.tagName.equalsIgnoreCase("ellipse") then
            (for
              cx <- num(e, "cx")
              cy <- num(e, "cy")
              rx <- num(e, "rx")
              ry <- num(e, "ry")
            yield Vector(Vec(cx - rx, cy), Vec(cx + rx, cy), Vec(cx, cy - ry), Vec(cx, cy + ry))).getOrElse(Vector.empty)
          else if e.tagName.equalsIgnoreCase("path") then BendPath.pathPoints(e.getAttribute("d"))
          else pointsOf(e.getAttribute("points"))
      def tip(at: Vec, base: Vec, other: Vec): Vec =
        (at - base).unit.filter(_ => (at - base).length > 0.5).fold(at): dir =>
          val own = points.filter(p => (p - at).length <= (p - other).length)
          own.map(p => (p - base).dot(dir)).maxOption.fold(at)(far => base + dir * far)
      val head = tip(headTip, old.last.p1, tailTip)
      val tail = tip(tailTip, old.head.p0, headTip)
      current.copy(startPoint = Point(tail.x, -tail.y), endPoint = Point(head.x, -head.y))

  /** The center of a decoration: the center of its box. */
  private def centerOf(e: dom.Element): Option[Vec] =
    val points =
      if e.tagName.equalsIgnoreCase("ellipse") then (num(e, "cx") zip num(e, "cy")).map((x, y) => Vec(x, y)).toVector
      else if e.tagName.equalsIgnoreCase("path") then BendPath.pathPoints(e.getAttribute("d"))
      else pointsOf(e.getAttribute("points"))
    Option.when(points.nonEmpty)(NodeShape.Polygon(points).center)

  /** Move each point of a decoration with `move`. */
  private def mapShape(e: dom.Element, move: Vec => Vec): Unit =
    if e.tagName.equalsIgnoreCase("ellipse") then
      for
        cx <- num(e, "cx")
        cy <- num(e, "cy")
      do
        val c = move(Vec(cx, cy))
        e.setAttribute("cx", fmt(c.x))
        e.setAttribute("cy", fmt(c.y))
    else if e.tagName.equalsIgnoreCase("path") then
      Option(e.getAttribute("d")).foreach(d => e.setAttribute("d", mapPathPoints(d, move)))
    else
      val points = pointsOf(e.getAttribute("points")).map(move)
      e.setAttribute("points", points.map(p => s"${fmt(p.x)},${fmt(p.y)}").mkString(" "))

  private val Number = """-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""".r

  /** The points of a `points` attribute: `x,y x,y ...`. */
  private[graphviz] def pointsOf(text: String): Vector[Vec] =
    Option(text).fold(Vector.empty): t =>
      val values = Number.findAllIn(t).flatMap(_.toDoubleOption).toVector
      values.grouped(2).collect { case Vector(x, y) => Vec(x, y) }.toVector


  /** The path data with each pair of numbers moved. Graphviz writes a decoration path with absolute `M` and `C`
    * commands, so each pair is a point.
    */
  private[graphviz] def mapPathPoints(d: String, move: Vec => Vec): String =
    val tokens = """[A-Za-z]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?""".r.findAllIn(d).toVector
    val out    = Vector.newBuilder[String]
    var pending = Option.empty[Double]
    tokens.foreach: token =>
      token.toDoubleOption match
        case None => pending = None; out += token
        case Some(v) =>
          pending match
            case None => pending = Some(v)
            case Some(x) =>
              val p = move(Vec(x, v))
              out += s"${fmt(p.x)},${fmt(p.y)}"
              pending = None
    out.result().mkString(" ")

  private def num(e: dom.Element, name: String): Option[Double] = Option(e.getAttribute(name)).flatMap(_.trim.toDoubleOption)

  private[graphviz] def fmt(v: Double): String = (math.round(v * 1000).toDouble / 1000).toString

end BendRender
