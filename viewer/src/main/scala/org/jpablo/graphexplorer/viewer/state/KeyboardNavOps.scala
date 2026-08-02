package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.utils.{ClientPoint, DistanceUtils}
import org.scalajs.dom

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
  * endpoint lies within an alignment cone around that direction:
  *   - exactly one candidate → jump straight to the far node;
  *   - several → select the best-aligned ARROW first (you see which edge you are
  *     about to follow). The fan spreads perpendicular to the travel direction,
  *     so the PERPENDICULAR keys walk the fan, the original key continues to the
  *     far node, and its opposite backs out to the origin.
  *
  * From an ARROW selected by hand (no pending fan), a direction key moves to the
  * endpoint lying that way.
  *
  * All geometry is read from the rendered SVG (client rects), snapshotted ONCE
  * per keypress — backend-agnostic and true to what the user sees at any zoom.
  * The fan itself is never cached: it is a deterministic function of
  * (origin, direction) and is recomputed per press from the current graph and
  * geometry, so there is nothing to go stale when the diagram changes.
  */
trait KeyboardNavOps:
  this: ViewerState =>

  /** Where navigation starts: the element the user last clicked or navigation
    * last landed on. Only an element that is still IN the selection counts; a
    * single-element selection is its own cursor; a multi-selection with no
    * valid cursor does NOT navigate (never `Set.head` — an unordered set has no
    * deterministic "last element", and guessing would collapse the selection
    * from an origin the user cannot predict).
    */
  private val navCursorV = Var[Option[ElementId]](None)

  /** Click paths call this so click-then-navigate starts from the click. Only
    * for elements the click left SELECTED — a shift-click deselect must not
    * claim the cursor (the previous one may still be valid).
    */
  def navCursorSet(id: ElementId): Unit = navCursorV.set(Some(id))

  /** The pending two-step move: only the origin node and pressed direction.
    * The candidate fan is deliberately NOT stored — see the class comment.
    */
  private case class NavContext(origin: NodeId, dir: NavDirection)
  private val navContextV = Var[Option[NavContext]](None)

  /** Marker for the one selection write keyboardNav itself is about to make,
    * so the invalidation subscription below can tell it apart. */
  private var navOwnWrite: Option[ElementId] = None

  // Any selection change NOT made by keyboardNav invalidates the pending fan:
  // the guard-by-coincidence alternative (comparing the selected arrow to a
  // remembered one) resurrects a dead fan when the user hand-clicks the same
  // arrow much later. The marker is consumed on the first change either way.
  locally {
    selection.signal.changes.foreach { sel =>
      val own = navOwnWrite
      navOwnWrite = None
      val isOwn = own.exists(id => sel.size == 1 && sel.contains(id))
      if !isOwn then navContextV.set(None)
    }(using owner)
  }

  object keyboardNav:

    /** Candidates must be aligned with the press, not merely in its half-plane:
      * cos ≥ 0.17 (~80° cone per side). A lone near-vertical edge with a 1px
      * horizontal jitter must not become "the single rightward candidate" and
      * jump the selection visibly downward.
      */
    private val MinAlignment = 0.17

    /** Client-px margin: a landed element closer than this to the canvas edge
      * (or outside it) triggers a pan that brings it back inside. */
    private val VisibleMargin = 40.0

    def navigate(dir: NavDirection): Unit =
      val sel = selection.now()
      val cursorOpt = navCursorV
        .now()
        .filter(sel.contains)
        .orElse(if sel.size == 1 then Some(sel.head) else None)
      for cursor <- cursorOpt do
        cursor match
          case _: GroupId => () // groups have no arrow topology to follow
          case _ =>
            // One geometry snapshot per press: a single findAll pass instead of
            // one whole-SVG query per candidate (the Mermaid strategy has no id
            // selector, so per-element queries degrade to full extractions).
            val boxes   = boxesSnapshot()
            val centers = boxes.view.mapValues(_.center).toMap
            cursor match
              case n: NodeId  => fromNode(n, dir, centers, boxes)
              case a: ArrowId => fromArrow(a, dir, centers)
              case _          => ()

    private case class Cand(arrow: ArrowId, far: NodeId, center: ClientPoint)

    /** An element's screen box. Centres alone cannot answer "is that node
      * BESIDE this one" — see [[stepAbreast]], where a 45° cone over centres
      * happily jumped from a node to one a whole rank above it. */
    private case class NavBox(l: Double, t: Double, r: Double, b: Double):
      def center: ClientPoint = ClientPoint((l + r) / 2.0, (t + b) / 2.0)

    /** The ordered candidate fan from `n` toward `dir`, or None when the
      * rendered geometry disagrees with the current graph (an async re-render
      * in flight: a node in the graph but absent from — or zero-sized in — the
      * old DOM). Navigating on partial geometry mis-aims, so the caller must
      * abort rather than silently drop the missing candidates: dropping can
      * leave a "lone" survivor that the single-candidate rule jumps through.
      */
    private def candidatesFor(
        n: NodeId,
        dir: NavDirection,
        centers: Map[ElementId, ClientPoint]
    ): Option[Vector[Cand]] =
      centers.get(n).flatMap: c0 =>
        val g = visibleGraphNow()
        // A selected ROW of a record/table narrows the topology to its own
        // edges: the arrows attached at its port, not every arrow the record
        // carries. Without this, arrowing off a selected row followed whichever
        // of the whole node's edges happened to point that way — a row with one
        // outgoing edge could hand you an edge belonging to a different row.
        // `None` = no row scope (no cell selected, or a portless row, which has
        // no edges of its own); see recordCells.selectedCellArrows.
        val incident =
          recordCells
            .selectedCellArrows(g)
            .filter(_ => recordCells.selectedCellNode.contains(n))
            .getOrElse(g.incidentArrows(n))
            .filter(a => a.source != a.target)
        // all-or-nothing: every incident far endpoint must resolve, or we abort
        val resolved = incident.map: a =>
          val far = a.otherEnd(n)
          centers.get(far).map(c => Cand(a.id, far, c))
        Option.unless(resolved.exists(_.isEmpty)):
          resolved.flatten
            .filter: c =>
              val (vx, vy) = (c.center.x - c0.x, c.center.y - c0.y)
              val len      = DistanceUtils.distance(c0.toTuple, c.center.toTuple) max 1e-9
              (vx * dir.dx + vy * dir.dy) / len >= MinAlignment
            // cycling order: along the perpendicular axis, ascending screen coord
            .sortBy(c => if dir.horizontal then c.center.y else c.center.x)

    private def fromNode(
        n: NodeId,
        dir: NavDirection,
        centers: Map[ElementId, ClientPoint],
        boxes: Map[ElementId, NavBox]
    ): Unit =
      for
        c0    <- centers.get(n)
        cands <- candidatesFor(n, dir, centers)
      do
        cands match
          case Vector() => stepAbreast(n, dir, centers, boxes) // no arrow that way — try the peer beside us
          case Vector(only) => moveToNode(only.far, centers) // single arrow: follow it through
          case _ =>
            // initial pick: best angular alignment with the press, then nearest
            def score(c: Cand) =
              val len = DistanceUtils.distance(c0.toTuple, c.center.toTuple) max 1e-9
              val (vx, vy) = (c.center.x - c0.x, c.center.y - c0.y)
              (-(vx * dir.dx + vy * dir.dy) / len, len)
            val idx = cands.indices.minBy(i => score(cands(i)))
            navContextV.set(Some(NavContext(n, dir)))
            selectArrow(cands(idx).arrow, centers)

    /** No arrow leads that way, so step to the node that simply LIES that way —
      * the peer abreast of this one.
      *
      * Siblings in a tree share a parent and nothing else: there is no edge
      * between them, so following arrows meant going UP to the parent and back
      * DOWN through its fan just to reach the box next door. That is the move
      * this removes.
      *
      * Deliberately not defined as "shares a predecessor". This module is
      * screen-geometric throughout — Right means "toward what I see on the
      * right", identically for rankdir=LR, TB or Mermaid — and the graph
      * relation gets the obvious cases wrong in both directions: two boxes side
      * by side under DIFFERENT parents are visually peers and would be excluded,
      * while a sibling that layout placed far away would be included.
      *
      * "Beside" is EXTENT OVERLAP, not an angle. A cone over centres — even a
      * tight 45° one — is not the same question, and gets it visibly wrong: a
      * node one rank down and one column left sits at almost exactly 45°, so
      * Right teleported from the bottom of one branch to the middle of another
      * (measured: cos 0.707 against a 0.7 threshold). Requiring the candidate's
      * perpendicular extent to overlap this node's asks the question actually
      * being asked — is it on my row? — and has no threshold to tune.
      *
      * Among the peers on that side, the nearest by GAP wins, so a press steps
      * one column at a time instead of leaping to the far edge.
      *
      * A pure fallback: it runs only where a press does nothing today, so no
      * existing move changes.
      */
    private def stepAbreast(
        n: NodeId,
        dir: NavDirection,
        centers: Map[ElementId, ClientPoint],
        boxes: Map[ElementId, NavBox]
    ): Unit =
      for o <- boxes.get(n) do
        // Strictly past this node's edge, so an overlapping neighbour never
        // counts as "beside" — and this node can never be its own answer.
        def beyond(b: NavBox) = dir match
          case NavDirection.NavLeft  => b.r <= o.l
          case NavDirection.NavRight => b.l >= o.r
          case NavDirection.NavUp    => b.b <= o.t
          case NavDirection.NavDown  => b.t >= o.b
        def onMyRow(b: NavBox) =
          if dir.horizontal then b.t < o.b && b.b > o.t else b.l < o.r && b.r > o.l
        def gap(b: NavBox) = dir match
          case NavDirection.NavLeft  => o.l - b.r
          case NavDirection.NavRight => b.l - o.r
          case NavDirection.NavUp    => o.t - b.b
          case NavDirection.NavDown  => b.t - o.b
        val peers = visibleGraphNow().nodeIds.iterator
          .filter(_ != n)
          .flatMap(id => boxes.get(id).map(id -> _))
          .filter((_, b) => onMyRow(b) && beyond(b))
          .toVector
        if peers.nonEmpty then moveToNode(peers.minBy((_, b) => gap(b))._1, centers)

    private def fromArrow(a: ArrowId, dir: NavDirection, centers: Map[ElementId, ClientPoint]): Unit =
      // Re-derive the fan from (origin, dir) with CURRENT graph + geometry and
      // locate the selected arrow in it; a fan it no longer belongs to is stale.
      val resumed =
        for
          ctx   <- navContextV.now()
          cands <- candidatesFor(ctx.origin, ctx.dir, centers)
          idx = cands.indexWhere(_.arrow == a)
          if idx >= 0
        yield (ctx, cands, idx)
      resumed match
        case Some((ctx, cands, idx)) =>
          if ctx.dir.horizontal == dir.horizontal then
            // the travel axis: continue to the far node, or back out
            if dir == ctx.dir then moveToNode(cands(idx).far, centers)
            else moveToNode(ctx.origin, centers)
          else
            // the perpendicular axis walks the fan (ordered ascending, so
            // Down/Right advance and Up/Left retreat); no wrap-around.
            val i2 = idx + (if dir == NavDirection.NavDown || dir == NavDirection.NavRight then 1 else -1)
            if i2 >= 0 && i2 < cands.length then
              selectArrow(cands(i2).arrow, centers)
        case None =>
          navContextV.set(None)
          // arrow selected by hand: go to the endpoint lying in the pressed
          // direction (projection of source→target onto the press decides).
          for arrow <- visibleGraphNow().arrows.get(a) do
            if arrow.source == arrow.target then
              // A SELF-LOOP has one endpoint, so no direction tells its ends
              // apart: source→target is the zero vector and the projection
              // below is exactly 0, which took neither branch. Every arrow key
              // did nothing and the selection was stranded on the loop — the
              // one element you could reach by clicking and not leave by
              // keyboard. Any direction lands on its node, and navigation
              // continues from there as usual.
              moveToNode(arrow.source, centers)
            else
              for
                cs <- centers.get(arrow.source)
                ct <- centers.get(arrow.target)
              do
                val comp = (ct.x - cs.x) * dir.dx + (ct.y - cs.y) * dir.dy
                if comp > 0 then moveToNode(arrow.target, centers)
                else if comp < 0 then moveToNode(arrow.source, centers)

    private def moveToNode(n: NodeId, centers: Map[ElementId, ClientPoint]): Unit =
      navContextV.set(None)
      navOwnWrite = Some(n)
      selection.set2(n)
      navCursorV.set(Some(n))
      ensureVisible(n, centers)

    private def selectArrow(a: ArrowId, centers: Map[ElementId, ClientPoint]): Unit =
      navOwnWrite = Some(a)
      selection.set2(a)
      navCursorV.set(Some(a))
      ensureVisible(a, centers)

    /** Pan minimally so the landed element sits inside the canvas viewport —
      * otherwise repeated presses walk the selection offscreen with no
      * feedback. Wheel-delta semantics (positive = scroll right/down).
      */
    private def ensureVisible(id: ElementId, centers: Map[ElementId, ClientPoint]): Unit =
      for
        p     <- centers.get(id)
        cont  <- Option(dom.document.getElementById("canvas-container"))
        svgEl <- finalSVGNow()
      do
        val box = cont.getBoundingClientRect()
        val m   = VisibleMargin
        val dx =
          if p.x > box.right - m then p.x - (box.right - m)
          else if p.x < box.left + m then p.x - (box.left + m)
          else 0.0
        val dy =
          if p.y > box.bottom - m then p.y - (box.bottom - m)
          else if p.y < box.top + m then p.y - (box.top + m)
          else 0.0
        if dx != 0.0 || dy != 0.0 then
          panByClient(dx, dy, svgEl.ref.viewBox.baseVal)

    /** Screen-space centers of every rendered element, in ONE pass. Zero-sized
      * rects (detached or not-yet-laid-out elements) are dropped so they read
      * as "missing" and trigger the all-or-nothing abort in [[candidatesFor]].
      */
    /** One point per element, at the centre of the UNION of that id's rects.
      *
      * An ElementId can name SEVERAL elements. Mermaid draws a self-loop as
      * three sibling paths that deliberately resolve to one ArrowId (see
      * MermaidSelectionStrategy.extractArrowId), so the `.toMap` this replaces
      * kept whichever segment came last: a loop's navigation point sat on its
      * right-hand arc, and every directional distance to it was measured from
      * a corner of the shape rather than its middle.
      *
      * The union's centre, not the mean of the segments' centres — the three
      * arcs are unequal, so averaging leans toward whichever end of the loop
      * happens to be cut into more pieces.
      */
    private def boxesSnapshot(): Map[ElementId, NavBox] =
      finalSVGNow() match
        case None => Map.empty
        case Some(svgEl) =>
          SelectableElement
            .findAll(svgEl.ref, selectionStrategyNow())
            .flatMap: el =>
              val r = el.ref.getBoundingClientRect()
              // A zero-size rect has no position worth merging into a union.
              if r.width <= 0 && r.height <= 0 then None
              else Some(el.elementId -> (r.left, r.top, r.right, r.bottom))
            .groupMapReduce(_._1)(_._2): (a, b) =>
              (math.min(a._1, b._1), math.min(a._2, b._2), math.max(a._3, b._3), math.max(a._4, b._4))
            .view
            .mapValues { case (l, t, r, b) => NavBox(l, t, r, b) }
            .toMap

  end keyboardNav

end KeyboardNavOps
