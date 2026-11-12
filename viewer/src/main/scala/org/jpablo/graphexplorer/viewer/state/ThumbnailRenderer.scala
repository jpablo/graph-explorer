package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.telemetry.Telemetry
import org.scalajs.dom
import org.scalajs.dom.svg.SVG

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import scala.util.Try

/** Renders diagram text (DOT or Mermaid) into a standalone SVG element for library/thumbnail previews.
  *
  * This is a stateless batch renderer with no relation to the reactive editing pipeline in
  * [[InternalPhases]]. It intentionally keeps its own backend knowledge because it is a one-shot render,
  * not a live component.
  */
object ThumbnailRenderer:

  /** Upper bound on how long a card may sit on its skeleton waiting for idle
    * time. A library scrolled continuously never goes idle, and a thumbnail
    * that only appears once the user stops is worse than a slightly janky one.
    */
  private val IdleTimeoutMs = 400

  /** Defer `thunk` to the next idle period.
    *
    * A thumbnail render is ONE indivisible unit of work — a full graph layout,
    * and for Mermaid a layout that measures text through the DOM. The
    * IntersectionObserver that triggers it fires DURING the scroll that
    * revealed the card, so every millisecond of it lands on the frames the
    * reader is watching.
    *
    * A macrotask (`delay(0)`) was enough to let the browser paint BETWEEN
    * cards, which is why the batch no longer freezes as a whole — but it still
    * schedules the work ahead of the next frame, so each individual card is
    * free to blow the frame budget on its own. Idle time is the difference
    * between "not one huge stall" and "not on the critical path at all".
    * A/B over one scroll of the library, same page, same gesture: total
    * main-thread blocking 1143ms → 618ms.
    *
    * What this does NOT fix: the worst SINGLE frame (562ms → 585ms, i.e.
    * unchanged). One graph layout is indivisible — scheduling can move it off
    * the scroll but cannot shorten it. That needs a worker, or thumbnails
    * persisted so a cold library never lays anything out at all.
    *
    * The timeout is what keeps this from being a regression: under a scroll
    * that never settles, `requestIdleCallback` would otherwise starve.
    */
  private def onIdle[A](thunk: () => A)(using ExecutionContext): Future[A] =
    val p                       = Promise[A]()
    val w                       = dom.window.asInstanceOf[js.Dynamic]
    val run: js.Function0[Unit] = () => { p.complete(Try(thunk())); () }
    if js.typeOf(w.requestIdleCallback) == "function" then
      w.requestIdleCallback(run, js.Dynamic.literal(timeout = IdleTimeoutMs))
    else dom.window.setTimeout(run, 0) // Safari < 16.4 and friends
    p.future

  /** The SVG elements that put marks on the screen. */
  private val DrawableSelector =
    "path, rect, circle, ellipse, line, polyline, polygon, text, image, use, foreignObject"

  /** True when `svg` holds at least one element that draws a mark.
    *
    * A Mermaid render whose id was also on the page gave an SVG with only a
    * style and an empty group (see RenderIdClashSpec). That SVG has no viewBox,
    * and its card is blank. Each correct thumbnail holds a drawable element. A
    * Graphviz SVG always holds the polygon of its background. Each Mermaid
    * diagram kind draws its markers, shapes, and labels with these elements.
    *
    * The search includes defs and markers. Thus an empty diagram, for example
    * a flowchart with no nodes, keeps its blank thumbnail. The search stops at
    * the first match, and a correct SVG has one near its start. Thus the check
    * costs much less than the parse before it.
    */
  private[state] def hasDrawing(svg: dom.Element): Boolean =
    svg.querySelector(DrawableSelector) != null

  /** A render gave an SVG with no drawing (see [[hasDrawing]]). */
  private final class NoDrawingException(format: DiagramFormat)
      extends Exception(s"the $format thumbnail holds no drawing")

  /** Process diagram text (DOT or Mermaid) and return an SVG element.
    * Used for generating thumbnails on the library page.
    */
  def processDotText(
      graphviz:          Graphviz,
      dot:              DotText,
      telemetryContext: Seq[(String, Any)] = Nil
  )(using ExecutionContext): Signal[ReactiveSvgElement[SVG]] =
    val format = DiagramFormat.detect(dot.value)

    // Each event from here has the context of the caller. All events go
    // through this one function, so no call site can lose the diagram id.
    def log(name: String, extra: (String, Any)*): Unit =
      Telemetry.log(name, (telemetryContext ++ extra)*)

    def cacheFields = Seq("format" -> format.toString, "cacheSize" -> ThumbnailSvgCache.size)

    ThumbnailSvgCache.get(format, dot.value) match
      case Some(proto) =>
        log("thumb.cache.hit", cacheFields*)
        Signal.fromValue(ThumbnailSvgCache.cloneSvg(proto))

      case None =>
        log("thumb.cache.miss", cacheFields*)

        // Adopt a parsed SVG (from the persistent cache, or fresh off a
        // render) as this card's element, refilling the in-memory cache on the
        // way so a card scrolled past twice pays the lookup only once.
        def adopt(proto: dom.svg.SVG): ReactiveSvgElement[SVG] =
          ThumbnailSvgCache.put(format, dot.value, proto)
          log("thumb.cache.store", cacheFields*)
          ThumbnailSvgCache.cloneSvg(proto)

        // ONE svg-only render (`textToSvgOnly`) straight from the source text.
        // The old path laid the graph out TWICE — textToSimpleGraph (full
        // layout → dot_json → JSON parse), a ViewerGraph round-trip
        // re-serialized to DOT, then textToSvg (full layout again + json0 +
        // edge positions a static thumbnail never reads). Runs on idle (see
        // [[onIdle]]) so a card revealed mid-scroll does not spend the scroll's
        // own frames laying itself out. A failure propagates, reaching the
        // signal's error channel exactly as Signal.fromTry did.
        def renderDot(startedAt: Double): Future[String] =
          onIdle { () =>
            val svgStartedAt = Telemetry.nowMs()
            val resultTry    = graphviz.textToSvgOnly(dot)
            log("thumb.dot.textToSvg", "dtMs" -> (Telemetry.nowMs() - svgStartedAt), "ok" -> resultTry.isSuccess)
            log("thumb.dot.total", "dtMs" -> (Telemetry.nowMs() - startedAt), "ok" -> resultTry.isSuccess)
            resultTry.get.outerHTML
          }

        // Started on idle for the same reason as the DOT path, and with more at
        // stake: Mermaid measures every label through the DOM, and a getBBox
        // that follows an SVG text mutation costs ~1.1ms on the library page
        // against ~11µs in an isolated document — the price scales with the
        // node count of whatever document it renders into, and no amount of CSS
        // containment reduces it. Not rendering at all is the only real cure,
        // which is what the persistent cache buys on every visit after the first.
        def renderMermaid(startedAt: Double): Future[String] =
          log("thumb.mermaid.start", "sourceChars" -> dot.value.length)
          val backend = MermaidBackend()
          onIdle(() => backend.textToSvg(dot.value))
            .flatMap(identity)
            .map: r =>
              log("thumb.mermaid.done", "dtMs" -> (Telemetry.nowMs() - startedAt), "ok" -> true)
              r.svg.ref.outerHTML

        val startedAt = Telemetry.nowMs()

        // Render the source and parse the result. A result with no drawing is
        // a failed render: the disk cache does not get it, and the card shows
        // what it shows for other failures of this format. Only a result with
        // a drawing goes into the disk cache.
        def renderFresh(): Future[dom.svg.SVG] =
          val rendered = format match
            case DiagramFormat.DOT     => renderDot(startedAt)
            case DiagramFormat.Mermaid => renderMermaid(startedAt)
          rendered.map: svgHtml =>
            val proto = parseSVG(svgHtml).ref
            if !hasDrawing(proto) then
              log("thumb.render.noDrawing", "format" -> format.toString, "bytes" -> svgHtml.length)
              throw NoDrawingException(format)
            ThumbnailDiskCache.put(format, dot.value, svgHtml)
            proto

        // The persistent cache sits BETWEEN the in-memory one and the renderer:
        // a library seen before parses stored SVG and lays out nothing. A miss,
        // a failed open (private browsing), or a corrupt record all arrive here
        // as None and simply render — the store is an optimisation, never a
        // source of truth. A stored record that does not parse as SVG, or that
        // holds no drawing, is also a miss. Earlier versions stored SVGs with no
        // drawing, and such a record stays until the source changes. The fresh
        // render replaces the record.
        val parsed: Future[dom.svg.SVG] =
          ThumbnailDiskCache.get(format, dot.value).flatMap:
            case Some(stored) =>
              Try(parseSVG(stored).ref).toOption.filter(hasDrawing) match
                case Some(proto) =>
                  log("thumb.disk.hit", "format" -> format.toString, "bytes" -> stored.length)
                  Future.successful(proto)
                case None =>
                  log("thumb.disk.noDrawing", "format" -> format.toString, "bytes" -> stored.length)
                  renderFresh()
            case None =>
              log("thumb.disk.miss", "format" -> format.toString)
              renderFresh()

        // A Mermaid failure lands on the "No preview" placeholder rather than in
        // Airstream's error channel, which used to raise a global error per card
        // mount. DOT keeps propagating: a DOT thumbnail that cannot render is a
        // real parse failure worth surfacing. Until either resolves, the card
        // shows a skeleton — the pending state is the starting value, so it can
        // no longer be confused with a failed render.
        val safe = format match
          case DiagramFormat.Mermaid => parsed.map(adopt).recover { case _ => emptySvg }
          case DiagramFormat.DOT     => parsed.map(adopt)

        EventStream.fromFuture(safe).toSignal(skeletonSvg)

  /** Empty SVG placeholder for when rendering fails */
  private def emptySvg: ReactiveSvgElement[SVG] =
    import com.raquo.laminar.api.L.svg.*
    svg(
      width  := "100",
      height := "100",
      text(
        x          := "50",
        y          := "50",
        textAnchor := "middle",
        "No preview"
      )
    )

  /** Animated placeholder while a render is in flight (daisyUI `skeleton`). */
  private def skeletonSvg: ReactiveSvgElement[SVG] =
    import com.raquo.laminar.api.L.svg.*
    svg(cls := "skeleton", width := "100", height := "100")

end ThumbnailRenderer
