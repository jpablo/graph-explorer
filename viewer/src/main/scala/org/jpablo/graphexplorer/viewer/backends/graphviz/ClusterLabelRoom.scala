package org.jpablo.graphexplorer.viewer.backends.graphviz

import org.jpablo.graphexplorer.gxcore.layout.BendPath
import org.jpablo.graphexplorer.gxcore.layout.BendPath.Vec
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{Point, SimpleGraph, SimpleGraphObject}
import org.scalajs.dom

/** The room for the label of each group in the SVG of a free layout (ADR 0004).
  *
  * `dot` keeps room for the label of a group (a DOT cluster). `neato` draws the free layout. It draws each group as a
  * tight box around its nodes, and it puts the label on the top nodes. Thus a node covers the label, and a wide label
  * goes out of the box. This object changes each box of the SVG to the box that `dot` draws:
  *   - The box has a margin of 8 points around its nodes and around the box of each child group.
  *   - The box gets a band for the label: at the top (the default), or at the bottom for `labelloc=b`. The label goes
  *     to the middle of the band. `labeljust=l` puts it at the left, `labeljust=r` at the right, else at the center.
  *   - The box is at least as wide as the label and its two margins.
  *   - The sheet of the drawing and the size of the SVG grow, so that they hold each box.
  *
  * The nodes and the arrows do not move: the person put the nodes. Only the box, the label and the sheet change, in
  * the SVG. Each part of the canvas that reads a box reads it from the SVG, so it sees the new box.
  */
object ClusterLabelRoom:

  /** The margin of `dot` around the content of a group, in points. */
  val Margin = 8.0

  /** The pad of Graphviz around the drawing, in points (the default of the `pad` attribute). */
  private val Pad = 4.0

  /** The label of a group, from the `json0` output of Graphviz.
    *
    * @param size     the width and the height of the label, in points. None for a group with no label.
    * @param center   the center of the label that Graphviz drew, with the y axis up
    * @param top      true when the label is at the top (`labelloc` is not `b`)
    * @param just     the first letter of `labeljust`: `l`, `r`, or another letter for the center
    * @param children the names of the subgraphs in this group
    */
  case class GroupLabel(
      name:     String,
      size:     Option[(Double, Double)],
      center:   Option[Point],
      top:      Boolean,
      just:     Char,
      children: Vector[String]
  )

  /** The label of each subgraph of a render, by its name. A subgraph that is not a cluster is also here, because it
    * can hold a cluster.
    */
  def labels(graph: SimpleGraph): Map[String, GroupLabel] =
    val subgraphs = graph.objects.getOrElse(Nil).collect { case SimpleGraphObject.Cluster(c) => c }
    val names     = subgraphs.map(c => c._gvid -> c.name).toMap
    subgraphs.iterator
      .map: c =>
        def inches(v: Option[String]) = v.flatMap(_.trim.toDoubleOption).map(_ * 72)
        val size   = (inches(c.lwidth) zip inches(c.lheight)).filter((w, h) => w > 0 && h > 0)
        val center = c.lp.flatMap(parsePoint)
        val top    = !c.labelloc.exists(_.trim.toLowerCase.startsWith("b"))
        val just   = c.labeljust.flatMap(_.trim.toLowerCase.headOption).getOrElse('c')
        c.name -> GroupLabel(c.name, size, center, top, just, c.subgraphs.getOrElse(Nil).flatMap(names.get).toVector)
      .toMap

  private def parsePoint(text: String): Option[Point] =
    text.split(',') match
      case Array(x, y) => (x.trim.toDoubleOption zip y.trim.toDoubleOption).map(Point.apply)
      case _           => None

  /** A box in the frame of the SVG: the y axis goes down. */
  private case class Box(x0: Double, y0: Double, x1: Double, y1: Double) derives CanEqual:
    def width: Double              = x1 - x0
    def grow(d: Double): Box       = Box(x0 - d, y0 - d, x1 + d, y1 + d)
    def union(other: Box): Box     = Box(x0 min other.x0, y0 min other.y0, x1 max other.x1, y1 max other.y1)

  private object Box:
    def around(points: Seq[Vec]): Option[Box] =
      Option.when(points.nonEmpty)(Box(points.map(_.x).min, points.map(_.y).min, points.map(_.x).max, points.map(_.y).max))

  /** Give each group of the SVG of `drawn` the room for its label, as `dot` does. The SVG changes in place. */
  def applyTo(drawn: SvgWithPositions): SvgWithPositions =
    val root     = drawn.svg.ref
    val groups   = drawn.groupLabels
    val clusters = root.querySelectorAll("g.cluster").toSeq.collect { case e: dom.Element => e }
    val drawnByName =
      clusters.flatMap(e => Option(e.querySelector("title")).map(_.textContent -> e)).toMap
    val frames = drawnByName.flatMap((name, e) => frameOf(e).map(name -> _))

    // The groups that a group holds and that the SVG draws: a subgraph that is not drawn gives its own children.
    def drawnChildren(name: String, seen: Set[String]): Vector[String] =
      groups.get(name).fold(Vector.empty)(_.children).filterNot(seen.contains).flatMap: child =>
        if frames.contains(child) then Vector(child) else drawnChildren(child, seen + child)

    // The new box of each drawn group. A child box comes first, because the box of its parent holds it.
    val boxes = scala.collection.mutable.Map.empty[String, Box]
    def boxOf(name: String, seen: Set[String]): Option[Box] =
      boxes.get(name).orElse:
        frames.get(name).map: (_, frame) =>
          val content =
            drawnChildren(name, seen + name).flatMap(c => boxOf(c, seen + name)).foldLeft(frame.grow(Margin)): (b, child) =>
              b.union(child.grow(Margin))
          val box = groups.get(name).flatMap(_.size).fold(content)((w, h) => withLabel(content, w, h, groups(name)))
          boxes(name) = box
          box

    drawnByName.keys.foreach(boxOf(_, Set.empty))
    boxes.foreach: (name, box) =>
      for
        (shape, frame) <- frames.get(name)
        cluster        <- drawnByName.get(name)
      do
        reshape(shape, frame, box)
        for
          label    <- groups.get(name)
          (w, _)   <- label.size
          old      <- label.center
        do
          val next = labelCenter(box, w, label)
          moveTexts(cluster, next - Vec(old.x, -old.y))
    growSheet(root, boxes.values.toSeq)
    drawn

  /** The box `content` with a band for a label of `w` by `h` points, and at least as wide as the label. */
  private def withLabel(content: Box, w: Double, h: Double, label: GroupLabel): Box =
    val band  = h + Margin
    val tall  = if label.top then content.copy(y0 = content.y0 - band) else content.copy(y1 = content.y1 + band)
    val extra = (w + 2 * Margin - tall.width) max 0.0
    tall.copy(x0 = tall.x0 - extra / 2, x1 = tall.x1 + extra / 2)

  /** The center of the label in the box, in the frame of the SVG. */
  private def labelCenter(box: Box, w: Double, label: GroupLabel): Vec =
    val h    = label.size.fold(0.0)(_._2)
    val band = h + Margin
    val y    = if label.top then box.y0 + band / 2 else box.y1 - band / 2
    val x = label.just match
      case 'l' => box.x0 + Margin + w / 2
      case 'r' => box.x1 - Margin - w / 2
      case _   => (box.x0 + box.x1) / 2
    Vec(x, y)

  /** The outline of a drawn group and its box: the first polygon or path of the group. Graphviz draws a rounded group
    * as a path. A group with no outline (`peripheries=0`) has none.
    */
  private def frameOf(cluster: dom.Element): Option[(dom.Element, Box)] =
    Option(cluster.querySelector("polygon, path")).flatMap: shape =>
      val points =
        if shape.tagName.equalsIgnoreCase("path") then BendPath.pathPoints(shape.getAttribute("d"))
        else BendRender.pointsOf(shape.getAttribute("points"))
      Box.around(points).map(shape -> _)

  /** Move the outline `shape` from the box `from` to the box `to`. Each point moves with its nearest side, so the
    * corners of a rounded box keep their radius.
    */
  private def reshape(shape: dom.Element, from: Box, to: Box): Unit =
    val midX = (from.x0 + from.x1) / 2
    val midY = (from.y0 + from.y1) / 2
    def move(p: Vec): Vec =
      Vec(
        p.x + (if p.x <= midX then to.x0 - from.x0 else to.x1 - from.x1),
        p.y + (if p.y <= midY then to.y0 - from.y0 else to.y1 - from.y1)
      )
    if shape.tagName.equalsIgnoreCase("path") then
      Option(shape.getAttribute("d")).foreach(d => shape.setAttribute("d", BendRender.mapPathPoints(d, move)))
    else
      val points = BendRender.pointsOf(shape.getAttribute("points")).map(move)
      shape.setAttribute("points", points.map(p => s"${BendRender.fmt(p.x)},${BendRender.fmt(p.y)}").mkString(" "))

  /** Move each line of the label of `cluster` by `delta`. */
  private def moveTexts(cluster: dom.Element, delta: Vec): Unit =
    cluster.querySelectorAll("text").toSeq.collect { case t: dom.Element => t }.foreach: t =>
      for
        x <- num(t.getAttribute("x"))
        y <- num(t.getAttribute("y"))
      do
        t.setAttribute("x", BendRender.fmt(x + delta.x))
        t.setAttribute("y", BendRender.fmt(y + delta.y))

  private val Translate = """translate\(\s*(-?[\d.eE+-]+)[\s,]+(-?[\d.eE+-]+)\s*\)""".r
  private val Scale     = """scale\(\s*(-?[\d.eE+-]+)(?:[\s,]+(-?[\d.eE+-]+))?\s*\)""".r
  private val Rotate    = """rotate\(\s*(-?[\d.eE+-]+)""".r

  /** Grow the sheet of the drawing, the viewBox and the size of the SVG, so that they hold each of `boxes` with the
    * pad of Graphviz. A drawing with a rotation (`rotate=90`) keeps its sheet.
    *
    * The main group of Graphviz has the transform `scale(sx sy) rotate(0) translate(tx ty)`, and the viewBox
    * `vx vy W H` shows the sheet: the point `(vx, vy)` of the viewBox is the corner of the sheet.
    */
  private def growSheet(root: dom.Element, boxes: Seq[Box]): Unit =
    for
      graph     <- Option(root.querySelector(":scope > g.graph"))
      transform <- Option(graph.getAttribute("transform"))
      translate <- Translate.findFirstMatchIn(transform)
      tx        <- translate.group(1).toDoubleOption
      ty        <- translate.group(2).toDoubleOption
      if Rotate.findFirstMatchIn(transform).flatMap(_.group(1).toDoubleOption).forall(_ == 0)
      viewBox <- Option(root.getAttribute("viewBox")).map(_.trim.split("[\\s,]+").toVector.flatMap(_.toDoubleOption))
      if viewBox.size == 4
    do
      val (sx, sy) = Scale.findFirstMatchIn(transform).fold((1.0, 1.0)): m =>
        val x = m.group(1).toDoubleOption.getOrElse(1.0)
        (x, Option(m.group(2)).flatMap(_.toDoubleOption).getOrElse(x))
      if sx > 0 && sy > 0 then
        val Vector(vx, vy, w, h) = viewBox: @unchecked
        val sheet = Box(vx / sx - tx, vy / sy - ty, vx / sx - tx + w / sx, vy / sy - ty + h / sy)
        val next  = boxes.foldLeft(sheet)((s, b) => s.union(b.grow(Pad)))
        if next != sheet then
          val (nextW, nextH) = (next.width * sx, (next.y1 - next.y0) * sy)
          val (nextTx, nextTy) = (vx / sx - next.x0, vy / sy - next.y0)
          graph.setAttribute("transform", Translate.replaceFirstIn(transform, s"translate(${BendRender.fmt(nextTx)} ${BendRender.fmt(nextTy)})"))
          root.setAttribute("viewBox", s"${BendRender.fmt(vx)} ${BendRender.fmt(vy)} ${BendRender.fmt(nextW)} ${BendRender.fmt(nextH)}")
          scaleLength(root, "width", nextW / w)
          scaleLength(root, "height", nextH / h)
          Option(graph.querySelector(":scope > polygon")).foreach: polygon =>
            val corners = Vector(Vec(next.x0, next.y1), Vec(next.x0, next.y0), Vec(next.x1, next.y0), Vec(next.x1, next.y1), Vec(next.x0, next.y1))
            polygon.setAttribute("points", corners.map(p => s"${BendRender.fmt(p.x)},${BendRender.fmt(p.y)}").mkString(" "))

  private val Length = """\s*(-?[\d.]+(?:[eE][-+]?\d+)?)\s*([a-z%]*)\s*""".r

  /** Multiply the length attribute `name` of `e` (for example `262pt`) by `k`. */
  private def scaleLength(e: dom.Element, name: String, k: Double): Unit =
    Option(e.getAttribute(name)).foreach:
      case Length(value, unit) => value.toDoubleOption.foreach(v => e.setAttribute(name, BendRender.fmt(v * k) + unit))
      case _                   => ()

  private def num(text: String): Option[Double] = Option(text).flatMap(_.trim.toDoubleOption)

end ClusterLabelRoom
