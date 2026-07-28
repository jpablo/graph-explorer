package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.models.*

/** A keyboard-navigation direction, in SCREEN space: `dy` grows downward, like
  * client coordinates. Screen-geometric on purpose — Right means "toward what I
  * see on the right", identically for rankdir=LR, TB, or a Mermaid diagram.
  */
enum NavDirection(val dx: Double, val dy: Double) derives CanEqual:
  case NavLeft  extends NavDirection(-1, 0)
  case NavRight extends NavDirection(1, 0)
  case NavUp    extends NavDirection(0, -1)
  case NavDown  extends NavDirection(0, 1)

  def horizontal: Boolean = dy == 0

/** Arrow-key navigation over the diagram: walk from the selected element along
  * its arrows.
  *
  * From a NODE, the pressed direction gathers the incident arrows whose OTHER
  * endpoint lies in that half-plane of the screen:
  *   - exactly one candidate → jump straight to the far node;
  *   - several → select the best-aligned ARROW first (you see which edge you are
  *     about to follow). The fan spreads perpendicular to the travel direction,
  *     so the PERPENDICULAR keys walk the fan, the original key continues to the
  *     far node, and its opposite backs out to the origin.
  *
  * From an ARROW selected by hand (no pending fan), a direction key moves to the
  * endpoint lying that way.
  *
  * All geometry is read from the rendered SVG (client rects), not the layout
  * model — backend-agnostic and true to what the user sees at any zoom.
  */
trait KeyboardNavOps:
  this: ViewerState =>

  /** Where navigation starts: the element the user last clicked or navigation
    * last landed on. With a multi-selection this is "the last elem" the feature
    * spec names; it must still be IN the selection to count (see [[navigate]]).
    */
  private val navCursorV = Var[Option[ElementId]](None)

  /** Click paths call this so click-then-navigate starts from the click. */
  def navCursorSet(id: ElementId): Unit = navCursorV.set(Some(id))

  /** The pending fan of a two-step move: `candidates` are (arrow, far node)
    * ordered along the axis PERPENDICULAR to `dir`, `idx` points at the one
    * currently selected. Valid only while that arrow is still the selection.
    */
  private case class NavContext(
      origin:     NodeId,
      dir:        NavDirection,
      candidates: Vector[(ArrowId, NodeId)],
      idx:        Int
  )
  private val navContextV = Var[Option[NavContext]](None)

  object keyboardNav:

    def navigate(dir: NavDirection): Unit =
      val sel = selection.now()
      if sel.nonEmpty then
        val cursor = navCursorV.now().filter(sel.contains).getOrElse(sel.head)
        cursor match
          case n: NodeId  => fromNode(n, dir)
          case a: ArrowId => fromArrow(a, dir)
          case _: GroupId => () // groups have no arrow topology to follow

    private def fromNode(n: NodeId, dir: NavDirection): Unit =
      for c0 <- centerOf(n) do
        case class Cand(arrowId: ArrowId, other: NodeId, cx: Double, cy: Double)
        val cands: Vector[Cand] =
          visibleGraphNow().arrows.values.toVector
            .filter(a => (a.source == n || a.target == n) && a.source != a.target)
            .flatMap: a =>
              val other = if a.source == n then a.target else a.source
              centerOf(other).map((x, y) => Cand(a.id, other, x, y))
            // the pressed half-plane, judged by the FAR endpoint's position
            .filter(c => (c.cx - c0._1) * dir.dx + (c.cy - c0._2) * dir.dy > 0)
        cands match
          case Vector() => () // nothing that way
          case Vector(only) => moveToNode(only.other) // single arrow: follow it through
          case _ =>
            // initial pick: best angular alignment with the press, then nearest
            def score(c: Cand) =
              val (vx, vy) = (c.cx - c0._1, c.cy - c0._2)
              val len      = math.hypot(vx, vy) max 1e-9
              (-(vx * dir.dx + vy * dir.dy) / len, len)
            // cycling order: along the perpendicular axis, ascending screen coord
            val ordered = cands.sortBy(c => if dir.horizontal then c.cy else c.cx)
            val idx     = ordered.zipWithIndex.minBy((c, _) => score(c))._2
            navContextV.set(Some(NavContext(n, dir, ordered.map(c => (c.arrowId, c.other)), idx)))
            selectArrow(ordered(idx).arrowId)

    private def fromArrow(a: ArrowId, dir: NavDirection): Unit =
      navContextV.now() match
        case Some(ctx) if ctx.candidates.lift(ctx.idx).exists(_(0) == a) =>
          if ctx.dir.horizontal == dir.horizontal then
            // the travel axis: continue to the far node, or back out
            if dir == ctx.dir then moveToNode(ctx.candidates(ctx.idx)(1))
            else moveToNode(ctx.origin)
          else
            // the perpendicular axis walks the fan (ordered ascending, so
            // Down/Right advance and Up/Left retreat); no wrap-around.
            val i2 = ctx.idx + (if dir == NavDirection.NavDown || dir == NavDirection.NavRight then 1 else -1)
            if i2 >= 0 && i2 < ctx.candidates.length then
              navContextV.set(Some(ctx.copy(idx = i2)))
              selectArrow(ctx.candidates(i2)(0))
        case _ =>
          // arrow selected by hand: go to the endpoint lying in the pressed
          // direction (projection of source→target onto the press decides).
          for
            arrow <- visibleGraphNow().arrows.get(a)
            cs    <- centerOf(arrow.source)
            ct    <- centerOf(arrow.target)
          do
            val comp = (ct._1 - cs._1) * dir.dx + (ct._2 - cs._2) * dir.dy
            if comp > 0 then moveToNode(arrow.target)
            else if comp < 0 then moveToNode(arrow.source)

    private def moveToNode(n: NodeId): Unit =
      navContextV.set(None)
      selection.set2(n)
      navCursorV.set(Some(n))

    private def selectArrow(a: ArrowId): Unit =
      selection.set2(a)
      navCursorV.set(Some(a))

    /** Screen-space centre of an element's rendered box, via the same
      * strategy-driven lookup the selection machinery uses.
      */
    private def centerOf(id: ElementId): Option[(Double, Double)] =
      for
        svgEl <- finalSVGNow()
        el    <- SelectableElement.query(svgEl.ref, ElementIds.from(id), selectionStrategyNow()).headOption
      yield
        val r = el.ref.getBoundingClientRect()
        ((r.left + r.right) / 2.0, (r.top + r.bottom) / 2.0)

  end keyboardNav

end KeyboardNavOps
