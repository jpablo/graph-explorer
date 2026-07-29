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

  val ghostClass = "gx-exit-ghost"

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

  private def clientCenter(el: dom.Element): (Double, Double) =
    val r = el.getBoundingClientRect()
    (r.left + r.width / 2, r.top + r.height / 2)

  final case class Snapshot(
      boxes:   Map[String, (Double, Double)],
      edges:   Map[String, Vector[(Double, Double)]],
      ghosts:  Map[String, dom.Element],
      viewBox: Option[(Double, Double, Double, Double)]
  ):
    def isEmpty: Boolean = boxes.isEmpty && edges.isEmpty

  /** The drawable `<path>` of an edge element. Graphviz wraps it in a
    * `<g class="edge">`; a Mermaid flowchart edge IS the path itself. Only
    * real paths qualify — rects also implement getTotalLength, and sampling a
    * label-hit rect's perimeter makes a garbage tween source.
    */
  private def edgePathOf(ref: dom.Element): Option[dom.Element] =
    if ref.tagName.equalsIgnoreCase("path") then Some(ref)
    else Option(ref.querySelector("path"))

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
  def capture(oldSvg: dom.svg.SVG, strategy: SelectableElementStrategy): Snapshot =
    if unmeasurable(oldSvg) then return Snapshot(Map.empty, Map.empty, Map.empty, None)
    val vb      = oldSvg.viewBox.baseVal
    val viewBox = Some((vb.x, vb.y, vb.width, vb.height))
    val els     = SelectableElement.findAll(oldSvg, strategy)
    val boxes  = Map.newBuilder[String, (Double, Double)]
    val edges  = Map.newBuilder[String, Vector[(Double, Double)]]
    val ghosts = Map.newBuilder[String, dom.Element]
    els.foreach { se =>
      val key = se.elementId.value
      ghosts += key -> se.ref
      if se.arrowId.isDefined then
        edgePathOf(se.ref).foreach { path =>
          for ctm <- ctmOf(path); samples <- samplePath(path) do
            edges += key -> samples.map(ctm.toClient)
        }
        boxes += key -> clientCenter(se.ref) // fallback correlation for edges without a path
      else boxes += key -> clientCenter(se.ref)
    }
    Snapshot(boxes.result(), edges.result(), ghosts.result(), viewBox)

  /** Start the transition on the NEW svg (mounted, transform applied).
    * Returns a cancel function, or None when there is nothing to animate.
    */
  def animate(newSvg: dom.svg.SVG, strategy: SelectableElementStrategy, snap: Snapshot): Option[() => Unit] =
    if snap.isEmpty || unmeasurable(newSvg) then return None
    val els = SelectableElement.findAll(newSvg, strategy)
    if els.isEmpty then None
    else
      val newKeys = els.map(_.elementId.value).toSet
      // No overlap ⇒ a different document, not an edit — don't animate.
      if !els.exists(se => snap.boxes.contains(se.elementId.value)) then None
      else Some(start(newSvg, els, newKeys, snap))

  // ── the tween model ──────────────────────────────────────────────────────
  // `base` is the element's OWN transform attribute ("" if absent): Mermaid
  // positions nodes through it, so the tween must COMPOSE — write
  // "base translate(delta)" per frame and restore base at the end. Clobbering
  // it (the original implementation) worked for graphviz only by accident of
  // its absolute coordinates.

  private final case class BoxTween(g: dom.Element, base: String, dx: Double, dy: Double)
  private final case class HeadTween(el: dom.Element, base: String, dx: Double, dy: Double)

  private def baseTransformOf(el: dom.Element): String =
    Option(el.getAttribute("transform")).getOrElse("")

  private def restore(el: dom.Element, base: String): Unit =
    if base.isEmpty then el.removeAttribute("transform")
    else el.setAttribute("transform", base)
  private final case class EdgeTween(
      path:   dom.Element,
      finalD: String,
      from:   Vector[(Double, Double)],
      to:     Vector[(Double, Double)],
      heads:  Seq[HeadTween]
  )

  private def start(
      newSvg:  dom.svg.SVG,
      els:     Seq[SelectableElement],
      newKeys: Set[String],
      snap:    Snapshot
  ): () => Unit =
    val boxTweens  = Vector.newBuilder[BoxTween]
    val edgeTweens = Vector.newBuilder[EdgeTween]
    val enters     = Vector.newBuilder[dom.Element]

    els.foreach { se =>
      val key = se.elementId.value
      se.arrowId match
        case Some(_) =>
          val tweened = edgePathOf(se.ref).exists { path =>
            (for
              ctm       <- ctmOf(path)
              to        <- samplePath(path)
              oldClient <- snap.edges.get(key)
            yield
              val finalD = path.getAttribute("d")
              val from   = oldClient.map(ctm.toLocal)
              val d0     = (from.last._1 - to.last._1, from.last._2 - to.last._2)
              val heads = se.ref
                .querySelectorAll("polygon, ellipse")
                .map(h => HeadTween(h, baseTransformOf(h), d0._1, d0._2))
                .toSeq
              edgeTweens += EdgeTween(path, finalD, from, to, heads)
            ).isDefined
          }
          if !tweened then if !snap.boxes.contains(key) then enters += se.ref
        case None =>
          (snap.boxes.get(key), ctmOf(se.ref)) match
            case (Some((oldX, oldY)), Some(ctm)) =>
              val (newX, newY) = clientCenter(se.ref)
              val dx           = (oldX - newX) / ctm.a
              val dy           = (oldY - newY) / ctm.d
              if dx.abs > 0.01 || dy.abs > 0.01 then
                boxTweens += BoxTween(se.ref, baseTransformOf(se.ref), dx, dy)
            case _ => enters += se.ref
    }

    // Exit ghosts: everything that had a place in the old layout and none in
    // the new. Adopted (not cloned) — the old svg is on its way out anyway.
    val mainGroup = newSvg.querySelector("g")
    val ghostRefFrame =
      els.headOption.flatMap(se => ctmOf(se.ref)) // frame of mainGroup's children
    val ghosts: Vector[dom.Element] =
      if mainGroup == null then Vector.empty
      else
        (for
          frame        <- ghostRefFrame.toVector
          (key, el)    <- snap.ghosts.toVector.sortBy(_._1)
          if !newKeys.contains(key)
          oldClient    <- snap.boxes.get(key).toVector
        yield
          val bb           = el.asInstanceOf[js.Dynamic].getBBox().asInstanceOf[dom.SVGRect]
          val localCenter  = (bb.x + bb.width / 2, bb.y + bb.height / 2)
          val (tx, ty)     = frame.toLocal(oldClient._1, oldClient._2)
          val wrap         = dom.document.createElementNS("http://www.w3.org/2000/svg", "g")
          wrap.setAttribute("class", ghostClass)
          wrap.setAttribute("transform", s"translate(${tx - localCenter._1} ${ty - localCenter._2})")
          wrap.asInstanceOf[dom.html.Element].style.pointerEvents = "none"
          el.removeAttribute("id")
          el.setAttribute("class", ghostClass)
          wrap.appendChild(el)
          mainGroup.appendChild(wrap)
          wrap
        )

    val boxes   = boxTweens.result()
    val edges   = edgeTweens.result()
    val entered = enters.result()
    entered.foreach(_.asInstanceOf[dom.html.Element].style.opacity = "0")

    // The viewBox pops too: a diagram that grew a rank rescales EVERYTHING in
    // one frame while positions glide — the most visible jump of all. Tween
    // the frame along with the content.
    val vbFinal = newSvg.viewBox.baseVal
    val vbTween: Option[((Double, Double, Double, Double), (Double, Double, Double, Double))] =
      snap.viewBox
        .map(from => (from, (vbFinal.x, vbFinal.y, vbFinal.width, vbFinal.height)))
        .filter((from, to) => from != to)

    def ease(t: Double): Double =
      if t < 0.5 then 4 * t * t * t else 1 - math.pow(-2 * t + 2, 3) / 2

    def applyFrame(e: Double): Unit =
      val r = 1 - e
      vbTween.foreach { (f, t) =>
        newSvg.setAttribute(
          "viewBox",
          s"${f._1 + (t._1 - f._1) * e} ${f._2 + (t._2 - f._2) * e} ${f._3 + (t._3 - f._3) * e} ${f._4 + (t._4 - f._4) * e}"
        )
      }
      boxes.foreach(b => b.g.setAttribute("transform", s"${b.base} translate(${b.dx * r} ${b.dy * r})".trim))
      edges.foreach { et =>
        val pts = et.from.indices.map { i =>
          val (fx, fy) = et.from(i)
          val (tx, ty) = et.to(i)
          s"${fx + (tx - fx) * e},${fy + (ty - fy) * e}"
        }
        et.path.setAttribute("d", "M" + pts.head + "L" + pts.tail.mkString(" "))
        et.heads.foreach(h => h.el.setAttribute("transform", s"${h.base} translate(${h.dx * r} ${h.dy * r})".trim))
      }
      entered.foreach(_.asInstanceOf[dom.html.Element].style.opacity = (((e - 0.4) / 0.6) max 0.0 min 1.0).toString)
      ghosts.foreach(_.asInstanceOf[dom.html.Element].style.opacity = ((1 - e * 2) max 0.0).toString)

    def finish(): Unit =
      vbTween.foreach((_, t) => newSvg.setAttribute("viewBox", s"${t._1} ${t._2} ${t._3} ${t._4}"))
      boxes.foreach(b => restore(b.g, b.base))
      edges.foreach { et =>
        et.path.setAttribute("d", et.finalD)
        et.heads.foreach(h => restore(h.el, h.base))
      }
      entered.foreach(_.asInstanceOf[dom.html.Element].style.opacity = "")
      ghosts.foreach(w => if w.parentNode != null then w.parentNode.removeChild(w))

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

    applyFrame(0.0)
    rafId = dom.window.requestAnimationFrame(step)

    () =>
      if !done then
        done = true
        dom.window.cancelAnimationFrame(rafId)
        finish()
