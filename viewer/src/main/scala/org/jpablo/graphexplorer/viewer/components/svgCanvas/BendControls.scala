package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.gxcore.layout.BendPath.{NodeShape, Vec}
import org.jpablo.graphexplorer.gxcore.model.LayoutPoint
import org.jpablo.graphexplorer.viewer.backends.graphviz.BendRender
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.ArrowPosition
import org.jpablo.graphexplorer.viewer.models.ArrowId
import org.jpablo.graphexplorer.viewer.state.ManualLayoutOps
import org.jpablo.graphexplorer.viewer.utils.ClientPoint
import org.scalajs.dom

/** The handles of the bend points of the selected arrow in a free layout (ADR 0004, part 4).
  *
  * Each bend point has a small circle. A drag of a circle moves the bend, and a double-click on it removes the bend.
  * A drag on the path of the selected arrow adds a bend. The circles keep their screen size at each zoom
  * (`ScreenConstant`).
  */
object BendControls:

  private val SvgNs = "http://www.w3.org/2000/svg"

  /** The class of the group of one handle. `data-bend-index` holds the index of its bend. */
  val HandleClass = "gx-bend-handle"

  /** The class of the circle that follows the pointer during a drag of a bend. */
  val DragDotClass = "gx-bend-drag-dot"

  /** On the svg while a bend follows the pointer (see style.scss). */
  val DraggingClass = "gx-bend-dragging"

  /** The screen diameter of a handle, in client pixels. */
  private val SizePx = 10.0

  /** Draw a handle at each point of `bends` (points of the SVG), in place of the old handles. `onPress` gets the
    * index of the pressed handle and the event.
    */
  def refresh(mainGroup: dom.svg.G, bends: Vector[Vec], onPress: (Int, dom.MouseEvent) => Unit): Unit =
    mainGroup.querySelectorAll(s"g.$HandleClass").foreach(_.remove())
    if bends.nonEmpty then
      ScreenConstant.userPerPx(mainGroup).foreach: upp =>
        bends.zipWithIndex.foreach: (p, i) =>
          val g = disk(HandleClass, p, upp)
          g.setAttribute("data-bend-index", i.toString)
          g.addEventListener("mousedown", (ev: dom.MouseEvent) => onPress(i, ev))
          mainGroup.appendChild(g)

  /** One screen-constant circle at the point `p` of the SVG. */
  private def disk(cls: String, p: Vec, upp: Double): dom.Element =
    val g = dom.document.createElementNS(SvgNs, "g")
    g.setAttribute("class", cls)
    g.setAttribute("pointer-events", "all")
    val circle = dom.document.createElementNS(SvgNs, "circle")
    circle.setAttribute("r", "4")
    g.appendChild(circle)
    ScreenConstant.place(g, ScreenConstant.Anchored(p.x, p.y, 0, 0, SizePx, 8), upp)
    g

  /** The look of a drag of one bend: the path follows the pointer with no new render.
    *
    * @param edge    the arrow in the SVG
    * @param start   the position of the arrow in the SVG now
    * @param base    the stored bends of the arrow
    * @param index   the index of the bend that moves, or of the new bend
    * @param insert  true when the drag adds a new bend at `index`
    * @param origin  the place of the moving bend at the start, in layout points
    * @param pressAt the layout point under the pointer at the start
    */
  final class BendDrag(
      mainGroup: dom.svg.G,
      edge:      dom.Element,
      start:     ArrowPosition,
      source:    NodeShape,
      target:    NodeShape,
      val arrow: ArrowId,
      base:      Vector[LayoutPoint],
      index:     Int,
      insert:    Boolean,
      origin:    LayoutPoint,
      pressAt:   LayoutPoint,
      toLayout:  ClientPoint => LayoutPoint,
      toSvg:     LayoutPoint => Vec
  ):
    // The attributes that a reroute changes, so that a cancel can put them back.
    private val saved =
      (edge +: edge.querySelectorAll("*").toSeq.collect { case e: dom.Element => e }).map: e =>
        e -> Seq("d", "points", "cx", "cy", "x", "y").flatMap(n => Option(e.getAttribute(n)).map(n -> _))
    private var position = start
    private var moving   = Option.empty[LayoutPoint]
    private var dot      = Option.empty[dom.Element]

    /** The bends of the arrow with the moving bend at its place now. */
    def points: Vector[LayoutPoint] =
      moving.fold(base)(p => if insert then base.patch(index, Seq(p), 0) else base.updated(index, p))

    /** True when the bends are different from the stored bends. */
    def changed: Boolean = moving.isDefined && points != base

    /** Follow the pointer at `at`. Without `alt`, the bend snaps to the grid of the layout. */
    def update(at: ClientPoint, alt: Boolean): Unit =
      val now = toLayout(at)
      val raw = LayoutPoint(origin.x + now.x - pressAt.x, origin.y + now.y - pressAt.y)
      val p   = if alt then raw.rounded else ManualLayoutOps.snapped(raw)
      moving = Some(p)
      BendRender.reroute(edge, position, source, target, points.map(toSvg)).foreach(position = _)
      mainGroup.ownerSVGElement.classList.add(DraggingClass)
      val upp = ScreenConstant.userPerPx(mainGroup).getOrElse(1.0)
      val v   = toSvg(p)
      dot match
        case Some(d) => ScreenConstant.place(d, ScreenConstant.Anchored(v.x, v.y, 0, 0, SizePx, 8), upp)
        case None =>
          val d = disk(DragDotClass, v, upp)
          d.setAttribute("pointer-events", "none")
          mainGroup.appendChild(d)
          dot = Some(d)

    /** Put back the arrow as it was before the drag. */
    def cancel(): Unit =
      for (e, attrs) <- saved; (name, value) <- attrs do e.setAttribute(name, value)
      hold()

    /** The release stored the bends: keep the new path until the new render replaces this SVG. */
    def hold(): Unit =
      dot.foreach(_.remove())
      dot = None
      mainGroup.ownerSVGElement.classList.remove(DraggingClass)

  end BendDrag

end BendControls
