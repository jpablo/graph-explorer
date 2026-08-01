package org.jpablo.graphexplorer.viewer.components.svgCanvas

import org.jpablo.graphexplorer.viewer.components.selection.{SelectableElement, SelectableElementStrategy}
import org.scalajs.dom

import scala.scalajs.js

/** Animated transition between two consecutive layouts.
  *
  * dot re-optimizes globally, so a small edit can rearrange the whole drawing;
  * a hard swap reads as an unrelated diagram. This module makes the change
  * legible: every element present in BOTH layouts glides from its old place to
  * its new one, new elements fade in, removed ones fade out as ghosts.
  *
  * All correlation happens in CLIENT space (getBoundingClientRect / screen
  * CTMs): the two renders disagree about raw layout coordinates (graphviz
  * emits y relative to a per-render height, and the viewBox changes with the
  * bounding box), but "where it was on screen" is frame-independent. At apply
  * time old client geometry is mapped into the NEW svg's local units through
  * the inverse CTM, and a single rAF loop drives:
  *
  *   - boxes (nodes, clusters, collapsed proxies): FLIP — a translate from
  *     old-position-delta to zero. Keyed by id STRING, so a group and the
  *     proxy box standing for it (same id, different element kinds) morph
  *     into each other across collapse/expand.
  *   - edges: the path `d` is rebuilt each frame from a fixed number of
  *     samples lerped old→new (arrowhead polygons ride the endpoint delta);
  *     the true spline is restored at the end.
  *   - enters: opacity fade-in (second half); exits: the old elements are
  *     adopted into the new svg as inert ghosts and fade out (first half).
  *
  * The returned cancel function stops the loop and snaps everything to its
  * final geometry — called when the next render arrives mid-flight.
  */
object LayoutTransition:

  private val Samples    = 24
  private val DurationMs = 300.0

  val ghostClass = SelectableElement.exitGhostClass

  /** On the svg root while the tween runs: overlay controls (count badges) ride
    * their element's scale tween and smear — the stylesheet hides them under
    * this class until the layout settles. */
  val transitioningClass = "gx-transitioning"

  /** Dispatched on the svg when a transition ends (completed OR cancelled):
    * anything positioned from mount-time geometry — which the transition's
    * frame-0 contract pins to the OLD layout — recomputes on this signal.
    */
  val transitionEndEvent = "gx-transition-end"

  def reducedMotion: Boolean =
    dom.window.matchMedia("(prefers-reduced-motion: reduce)").matches

  /** Screen CTM as (scaleX, scaleY, translate) — our transforms never rotate
    * or skew, so b/c are ignored.
    */
  private final case class Ctm(a: Double, d: Double, e: Double, f: Double):
    def toClient(x: Double, y: Double): (Double, Double) = (a * x + e, d * y + f)
    def toLocal(cx: Double, cy: Double): (Double, Double) = ((cx - e) / a, (cy - f) / d)

  private def ctmOf(el: dom.Element): Option[Ctm] =
    val m = el.asInstanceOf[js.Dynamic].getScreenCTM()
    if m == null then None
    else Some(Ctm(m.a.asInstanceOf[Double], m.d.asInstanceOf[Double], m.e.asInstanceOf[Double], m.f.asInstanceOf[Double]))

  /** Client rect as (center x, center y, width, height). */
  private def clientBox(el: dom.Element): (Double, Double, Double, Double) =
    val r = el.getBoundingClientRect()
    (r.left + r.width / 2, r.top + r.height / 2, r.width, r.height)

  /** `boxes` are client rects (cx, cy, w, h): the size channel of the morph is
    * PER ELEMENT (old measured size over new measured size), which covers both
    * the global frame rescale and elements whose intrinsic size changed (a
    * cluster that grew a member). `edgeParts` are edge-label client rects,
    * keyed by edge and child index — labels are not on the sampled path and
    * would otherwise snap. `sheet` is the old background sheet's client rect
    * (graphviz draws the graph bb as a filled polygon — its instant resize
    * was a very visible pop).
    */
  final case class Snapshot(
      boxes:     Map[String, (Double, Double, Double, Double)],
      edges:     Map[String, Vector[(Double, Double)]],
      edgeParts: Map[String, (Double, Double, Double, Double)],
      ghosts:    Map[String, dom.Element],
      // Local (user-space) bbox per element, measured while the OLD svg is still
      // mounted: getBBox() on a detached element silently returns 0×0 in Chrome,
      // which placed every exit ghost at raw old-layout coordinates — visibly
      // displaced (typically to the right) before fading.
      ghostBBoxes: Map[String, (Double, Double, Double, Double)],
      sheet:     Option[(Double, Double, Double, Double)]
  ):
    def isEmpty: Boolean = boxes.isEmpty && edges.isEmpty

  /** The drawable `<path>` of an edge element. Graphviz wraps it in a
    * `<g class="edge">`; a Mermaid flowchart edge IS the path itself. Only
    * real paths qualify — rects also implement getTotalLength, and sampling a
    * label-hit rect's perimeter makes a garbage tween source.
    */
  private def edgePathOf(ref: dom.Element): Option[dom.Element] =
    if ref.tagName.equalsIgnoreCase("path") then Some(ref)
    else Option(ref.querySelector(SelectableElement.splineSelector))

  private def samplePath(path: dom.Element): Option[Vector[(Double, Double)]] =
    val p   = path.asInstanceOf[js.Dynamic]
    val len = p.getTotalLength().asInstanceOf[Double]
    if !(len > 0) then None
    else
      Some(
        (0 to Samples).toVector.map { i =>
          val pt = p.getPointAtLength(len * i / Samples)
          (pt.x.asInstanceOf[Double], pt.y.asInstanceOf[Double])
        }
      )

  /** True when `svg` has no rendered geometry (hidden tab/pane): every client
    * measurement would be zeros, and a transition built from those flies the
    * content across the canvas. */
  private def unmeasurable(svg: dom.svg.SVG): Boolean =
    val r = svg.getBoundingClientRect()
    r.width == 0 && r.height == 0

  /** Measure the OLD svg (still mounted) in client space. The element
    * references are kept: they become the exit ghosts.
    */
  /** The background sheet: graphviz emits the graph's bounding box as a
    * polygon directly under the root group. */
  private def sheetOf(svg: dom.svg.SVG): Option[dom.Element] =
    Option(svg.querySelector(":scope > g > polygon"))

  /** Keys for an edge's decorations: its i-th `<text>` (labels) and i-th
    * `<polygon>`/`<ellipse>` (arrowheads), paired by index across renders. */
  private def edgeTextKey(edgeKey: String, i: Int): String = s"$edgeKey##t$i"
  private def edgeHeadKey(edgeKey: String, i: Int): String = s"$edgeKey##h$i"

  /** Correlation keys for one render's elements, in document order.
    *
    * An ElementId is NOT unique in the DOM. Mermaid renders a self-loop as
    * three sibling paths — `<node>-cyclic-special-1`, `-mid`, `-2` — which all
    * resolve to the same ArrowId on purpose, so that selecting any of them
    * selects the whole loop. Every Snapshot map is keyed by that id, so two of
    * the three were silently overwritten: a departing self-loop had one segment
    * fade while the other two vanished on the spot, and a SURVIVING one tweened
    * all three segments away from whichever geometry happened to be written
    * last.
    *
    * Disambiguated by occurrence rather than by anything intrinsic, because the
    * segments have nothing else to tell them apart — and occurrence is stable:
    * Mermaid emits a loop's three paths in the same order every render. The
    * first occurrence keeps the bare id so single-element ids (every node, and
    * every ordinary edge) key exactly as before.
    *
    * `##s` and not `##`: [[edgeTextKey]] and [[edgeHeadKey]] already use
    * `##t$i` / `##h$i` in the same key space.
    */
  private def correlationKeys(els: Seq[SelectableElement]): Seq[(String, SelectableElement)] =
    val seen = scala.collection.mutable.Map.empty[String, Int]
    els.map: se =>
      val id = se.elementId.value
      val n  = seen.getOrElse(id, 0)
      seen(id) = n + 1
      (if n == 0 then id else s"$id##s$n", se)

  def capture(oldSvg: dom.svg.SVG, strategy: SelectableElementStrategy): Snapshot =
    if unmeasurable(oldSvg) then return Snapshot(Map.empty, Map.empty, Map.empty, Map.empty, Map.empty, None)
    val els         = SelectableElement.findAll(oldSvg, strategy)
    val boxes       = Map.newBuilder[String, (Double, Double, Double, Double)]
    val edges       = Map.newBuilder[String, Vector[(Double, Double)]]
    val edgeParts   = Map.newBuilder[String, (Double, Double, Double, Double)]
    val ghosts      = Map.newBuilder[String, dom.Element]
    val ghostBBoxes = Map.newBuilder[String, (Double, Double, Double, Double)]
    correlationKeys(els).foreach { (key, se) =>
      ghosts += key -> se.ref
      val bb = se.ref.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
      ghostBBoxes += key -> (bb.x, bb.y, bb.width, bb.height)
      if se.arrowId.isDefined then
        edgePathOf(se.ref).foreach { path =>
          for ctm <- ctmOf(path); samples <- samplePath(path) do
            edges += key -> samples.map(ctm.toClient)
        }
        se.ref.querySelectorAll("text").zipWithIndex.foreach { (t, i) =>
          edgeParts += edgeTextKey(key, i) -> clientBox(t)
        }
        se.ref.querySelectorAll("polygon, ellipse").zipWithIndex.foreach { (h, i) =>
          edgeParts += edgeHeadKey(key, i) -> clientBox(h)
        }
        boxes += key -> clientBox(se.ref) // fallback correlation for edges without a path
      else boxes += key -> clientBox(se.ref)
    }
    val sheet = sheetOf(oldSvg).map { p =>
      val r = p.getBoundingClientRect()
      (r.left, r.top, r.width, r.height)
    }
    Snapshot(boxes.result(), edges.result(), edgeParts.result(), ghosts.result(), ghostBBoxes.result(), sheet)

  /** Start the transition on the NEW svg (mounted, transform applied).
    * Returns a cancel function, or None when there is nothing to animate.
    */
  def animate(newSvg: dom.svg.SVG, strategy: SelectableElementStrategy, snap: Snapshot): Option[() => Unit] =
    if snap.isEmpty || unmeasurable(newSvg) then return None
    val els = SelectableElement.findAll(newSvg, strategy)
    if els.isEmpty then None
    else
      val keyed   = correlationKeys(els)
      val newKeys = keyed.map(_._1).toSet
      // No overlap ⇒ a different document, not an edit — don't animate.
      if !newKeys.exists(snap.boxes.contains) then None
      else Some(start(newSvg, keyed, newKeys, snap))

  // ── the tween model ──────────────────────────────────────────────────────
  // `base` is the element's OWN transform attribute ("" if absent): Mermaid
  // positions nodes through it, so the tween must COMPOSE — write
  // "base <tween>" per frame and restore base at the end. Clobbering it
  // (the original implementation) worked for graphviz only by accident of
  // its absolute coordinates.
  //
  // A box morphs on two channels, both EXACT at frame 0:
  //   - position: its center lerps in CLIENT space from the old center to the
  //     new one, converted into local units through the element's (constant)
  //     ctm — the viewBox never animates, so frame 0 reproduces the old
  //     client position to the pixel. (Animating the viewBox instead put a
  //     frame-origin error into every frame-0 position: the "shifted double".)
  //   - size: scale about its own center, running from oldSize/newSize
  //     (measured per element, per axis) to 1 — covering both the global
  //     frame rescale and intrinsic size changes (a cluster that grew).
  // The same tween serves nodes, clusters, arrowheads and edge labels.

  private final case class BoxTween(
      g:    dom.Element,
      base: String,
      inv:  Ctm,                 // the element's constant local↔client mapping
      cfx:  Double, cfy: Double, // final local center
      oldC: (Double, Double),    // client centers
      newC: (Double, Double),
      kx0:  Double, ky0: Double  // frame-0 scale: old client size / new client size
  )
  private final case class SheetTween(
      el:   dom.Element,
      inv:  Ctm,
      flx:  Double, fly: Double, // final local top-left
      oldR: (Double, Double, Double, Double), // client rects (x, y, w, h)
      newR: (Double, Double, Double, Double)
  )

  private def baseTransformOf(el: dom.Element): String =
    Option(el.getAttribute("transform")).getOrElse("")

  private def restore(el: dom.Element, base: String): Unit =
    if base.isEmpty then el.removeAttribute("transform")
    else el.setAttribute("transform", base)
  private final case class EdgeTween(
      path:   dom.Element,
      finalD: String,
      from:   Vector[(Double, Double)],
      to:     Vector[(Double, Double)]
  )

  /** A departing element, pinned to the DRAWING rather than to the glass.
    *
    * It used to be pinned to the glass: the transform was written once, with
    * `kg*` cancelling the new frame's scale so the ghost held its old on-screen
    * size, and only opacity animated after that. Exact at frame 0 — which is
    * what the compensation is for — but it also froze the ghost for its whole
    * life, and a re-fit then slid the drawing out from under it. Deleting a
    * node zooms auto-fit in, so the departing arrow's stem stayed put while the
    * node it hung from grew across it: measured off a 60fps capture, the stem
    * held one screen position for 8 frames while the node's bottom edge swept
    * ~38px past it and ended up INSIDE the node. A remnant beside the drawing
    * reads as "that's leaving"; one crossing a node's border reads as a fault.
    *
    * So interpolate the compensation away rather than holding it. At t=0 the
    * ghost is exactly where it was on screen — frame-0 exactness preserved — and
    * at t=1 it carries no compensation at all, sitting at its own diagram
    * coordinates under the new framing, which is where the drawing has moved to.
    * In between it glides and scales with its neighbours while it fades.
    */
  private final case class GhostTween(
      wrap: dom.Element,
      cx:   Double, // the ghost's own bbox centre, in its local coordinates
      cy:   Double,
      tx:   Double, // where that centre sat on screen, mapped into mainGroup
      ty:   Double,
      kgx:  Double, // scale cancelling the new frame; 1 means "no correction"
      kgy:  Double
  ):
    def transformAt(t: Double): String =
      val kx = kgx + (1 - kgx) * t
      val ky = kgy + (1 - kgy) * t
      // The centre travels from its old screen spot to its natural one; the
      // translate is then whatever puts it there at the current scale.
      val px = tx + (cx - tx) * t
      val py = ty + (cy - ty) * t
      s"translate(${px - kx * cx} ${py - ky * cy}) scale($kx $ky)"

  private def start(
      newSvg:  dom.svg.SVG,
      // Already paired with their correlation keys — see [[correlationKeys]].
      // Recomputing them here would work but invites the two sides drifting.
      keyed:   Seq[(String, SelectableElement)],
      newKeys: Set[String],
      snap:    Snapshot
  ): () => Unit =
    val boxTweens  = Vector.newBuilder[BoxTween]
    val edgeTweens = Vector.newBuilder[EdgeTween]
    val enters     = Vector.newBuilder[dom.Element]

    def rectTween(el: dom.Element, old: (Double, Double, Double, Double)): Unit =
      ctmOf(el).foreach { ctm =>
        val (oldX, oldY, oldW, oldH)     = old
        val (newX, newY, newW, newH)     = clientBox(el)
        val (cfx, cfy)                   = ctm.toLocal(newX, newY)
        val kx0                          = if newW > 0 then oldW / newW else 1.0
        val ky0                          = if newH > 0 then oldH / newH else 1.0
        boxTweens += BoxTween(el, baseTransformOf(el), ctm, cfx, cfy, (oldX, oldY), (newX, newY), kx0, ky0)
      }

    keyed.foreach { (key, se) =>
      se.arrowId match
        case Some(_) =>
          val tweened = edgePathOf(se.ref).exists { path =>
            (for
              ctm       <- ctmOf(path)
              to        <- samplePath(path)
              oldClient <- snap.edges.get(key)
            yield edgeTweens += EdgeTween(path, path.getAttribute("d"), oldClient.map(ctm.toLocal), to)
            ).isDefined
          }
          // Arrowheads and labels morph as rects of their own — a head that
          // only translated kept its NEW size and orientation at frame 0.
          se.ref.querySelectorAll("text").zipWithIndex.foreach { (t, i) =>
            snap.edgeParts.get(edgeTextKey(key, i)).foreach(rectTween(t, _))
          }
          se.ref.querySelectorAll("polygon, ellipse").zipWithIndex.foreach { (h, i) =>
            snap.edgeParts.get(edgeHeadKey(key, i)).foreach(rectTween(h, _))
          }
          if !tweened then if !snap.boxes.contains(key) then enters += se.ref
        case None =>
          snap.boxes.get(key) match
            case Some(old) => rectTween(se.ref, old)
            case None      => enters += se.ref
    }

    // Exit ghosts: everything that had a place in the old layout and none in
    // the new. Adopted (not cloned) — the old svg is on its way out anyway.
    val mainGroup = newSvg.querySelector("g")
    // The frame a ghost's transform is READ IN — mainGroup's user space, since
    // that is what the wrap is appended to. Take it from mainGroup itself, not
    // from `els.head`: an element only shares mainGroup's frame when it carries
    // no transform of its own, which is true of an edge path and false of a
    // `g.node`. Mermaid emits `g.edgePaths` before `g.nodes`, so `els.head` was
    // an untransformed path as long as ANY edge survived, and the old reading
    // was accidentally right. Delete the last edge and `els.head` became the
    // node — whose `translate(...)` then offset every ghost by exactly that
    // much, parking the departing edge up and to the left of the drawing.
    val ghostRefFrame = Option(mainGroup).flatMap(ctmOf)
    val ghosts: Vector[GhostTween] =
      if mainGroup == null then Vector.empty
      else
        (for
          frame        <- ghostRefFrame.toVector
          (key, el)    <- snap.ghosts.toVector.sortBy(_._1)
          if !newKeys.contains(key)
          oldClient    <- snap.boxes.get(key).toVector
          // bbox from capture time: el is detached by now, and getBBox() on a
          // detached element is 0×0 — the ghost then rendered displaced.
          (bbX, bbY, bbW, bbH) <- snap.ghostBBoxes.get(key).toVector
        yield
          val localCenter = (bbX + bbW / 2, bbY + bbH / 2)
          val (tx, ty)    = frame.toLocal(oldClient._1, oldClient._2)
          // Rendered in the NEW frame, the ghost's size would be newScale ×
          // its local size — scale it so it keeps its OLD on-screen size.
          val kgx  = if bbW > 0 && frame.a != 0 then oldClient._3 / (bbW * frame.a) else 1.0
          val kgy  = if bbH > 0 && frame.d != 0 then oldClient._4 / (bbH * frame.d) else 1.0
          val wrap = dom.document.createElementNS("http://www.w3.org/2000/svg", "g")
          wrap.setAttribute("class", ghostClass)
          val tween = GhostTween(wrap, localCenter._1, localCenter._2, tx, ty, kgx, kgy)
          wrap.setAttribute("transform", tween.transformAt(0.0))
          wrap.asInstanceOf[dom.html.Element].style.pointerEvents = "none"
          el.removeAttribute("id")
          // ADD the marker, never replace the class list: Mermaid's styling is
          // class-scoped CSS inside the svg, and a re-classed ghost rendered
          // with default fills — solid black rects and filled edge paths.
          // findAll excludes ghosts by this marker (SelectableElement).
          el.setAttribute("class", s"${Option(el.getAttribute("class")).getOrElse("")} $ghostClass".trim)
          wrap.appendChild(el)
          mainGroup.appendChild(wrap)
          tween
        )

    val boxes   = boxTweens.result()
    val edges   = edgeTweens.result()
    val entered = enters.result()
    entered.foreach(_.asInstanceOf[dom.html.Element].style.opacity = "0")

    // The background sheet (the graph's white bounding-box polygon) resizes
    // instantly at swap otherwise — a full-canvas pop.
    val sheetTween: Option[SheetTween] =
      for
        oldR  <- snap.sheet
        el    <- sheetOf(newSvg)
        inv   <- ctmOf(el)
      yield
        val r          = el.getBoundingClientRect()
        val (flx, fly) = inv.toLocal(r.left, r.top)
        SheetTween(el, inv, flx, fly, oldR, (r.left, r.top, r.width, r.height))

    def ease(t: Double): Double =
      if t < 0.5 then 4 * t * t * t else 1 - math.pow(-2 * t + 2, 3) / 2

    def applyFrame(e: Double): Unit =
      boxes.foreach { b =>
        val cx       = b.oldC._1 + (b.newC._1 - b.oldC._1) * e
        val cy       = b.oldC._2 + (b.newC._2 - b.oldC._2) * e
        val (lx, ly) = b.inv.toLocal(cx, cy)
        val kx       = b.kx0 + (1 - b.kx0) * e
        val ky       = b.ky0 + (1 - b.ky0) * e
        b.g.setAttribute("transform", s"${b.base} translate(${lx - kx * b.cfx} ${ly - ky * b.cfy}) scale($kx $ky)".trim)
      }
      sheetTween.foreach { st =>
        val x  = st.oldR._1 + (st.newR._1 - st.oldR._1) * e
        val y  = st.oldR._2 + (st.newR._2 - st.oldR._2) * e
        val kx = (st.oldR._3 + (st.newR._3 - st.oldR._3) * e) / st.newR._3
        val ky = (st.oldR._4 + (st.newR._4 - st.oldR._4) * e) / st.newR._4
        val (lx, ly) = st.inv.toLocal(x, y)
        st.el.setAttribute("transform", s"translate(${lx - kx * st.flx} ${ly - ky * st.fly}) scale($kx $ky)")
      }
      edges.foreach { et =>
        val pts = et.from.indices.map { i =>
          val (fx, fy) = et.from(i)
          val (tx, ty) = et.to(i)
          s"${fx + (tx - fx) * e},${fy + (ty - fy) * e}"
        }
        et.path.setAttribute("d", "M" + pts.head + "L" + pts.tail.mkString(" "))
      }
      entered.foreach(_.asInstanceOf[dom.html.Element].style.opacity = (((e - 0.4) / 0.6) max 0.0 min 1.0).toString)
      // Ghosts move as well as fade: holding the compensation still would park
      // them on the glass while the drawing re-fits out from under them.
      ghosts.foreach: g =>
        g.wrap.setAttribute("transform", g.transformAt(e))
        g.wrap.asInstanceOf[dom.html.Element].style.opacity = ((1 - e * 2) max 0.0).toString

    def finish(): Unit =
      boxes.foreach(b => restore(b.g, b.base))
      sheetTween.foreach(st => st.el.removeAttribute("transform"))
      edges.foreach(et => et.path.setAttribute("d", et.finalD))
      entered.foreach(_.asInstanceOf[dom.html.Element].style.opacity = "")
      ghosts.foreach(g => if g.wrap.parentNode != null then g.wrap.parentNode.removeChild(g.wrap))
      newSvg.classList.remove(transitioningClass)
      newSvg.dispatchEvent(new dom.Event(transitionEndEvent))

    var rafId = 0
    var done  = false
    // The clock starts at the FIRST frame, not at setup: the synchronous
    // mount + measurement work preceding it can easily eat the whole
    // duration, which would finish the animation before it is ever seen.
    var t0 = -1.0
    lazy val step: js.Function1[Double, Unit] = (now: Double) =>
      if !done then
        if t0 < 0 then t0 = now
        val t = ((now - t0) / DurationMs) min 1.0
        applyFrame(ease(t))
        if t < 1.0 then rafId = dom.window.requestAnimationFrame(step)
        else
          done = true
          finish()

    newSvg.classList.add(transitioningClass)
    applyFrame(0.0)
    rafId = dom.window.requestAnimationFrame(step)

    () =>
      if !done then
        done = true
        dom.window.cancelAnimationFrame(rafId)
        finish()
