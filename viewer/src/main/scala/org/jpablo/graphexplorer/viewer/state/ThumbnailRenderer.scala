package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.gxcore.layout.FreeLayout
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, DiagramMetadata}
import org.jpablo.graphexplorer.viewer.backends.{DefaultDiagramLanguages, DiagramFormat, DiagramLanguages, LocalImages}
import org.jpablo.graphexplorer.viewer.backends.graphviz.{Graphviz, GraphvizRenderer}
import org.jpablo.graphexplorer.viewer.backends.mermaid.{MermaidBackend, MermaidRenderer}
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.desktop.{DesktopFiles, DesktopIpc}
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.telemetry.Telemetry
import org.scalajs.dom
import org.scalajs.dom.svg.SVG

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** The inputs of a thumbnail, other than the source, that change the drawing of the view: the hidden elements, the
  * collapsed groups, and the selected renderer of the metadata, the layout of the manual mode, and the file of the
  * diagram on the desktop.
  *
  * [[of]] keeps only these parts. Thus a change of a different part (a tag, a note) does not draw a card again. A
  * layout that is not manual counts as the empty layout for the same reason.
  *
  * @param file the path of the file of the diagram on the desktop. An image with a path relative to the diagram
  *   comes from the folder of this file. A browser diagram has no file: its images are `data:` URIs.
  */
final case class ThumbnailView(metadata: DiagramMetadata, layout: DiagramLayout, file: Option[String] = None)
    derives CanEqual:

  /** The part of the cache key that this view gives. */
  def key: String =
    val refs = (metadata.hiddenElements.toVector.sorted.map("hide:" + _) ++
      metadata.collapsedGroups.toVector.sorted.map("fold:" + _) ++
      metadata.renderer.map("renderer:" + _).toVector ++
      file.map("file:" + _).toVector).mkString("\u0001")
    if layout.isManual then s"$refs\u0000layout:${DiagramLayout.render(layout)}" else refs

object ThumbnailView:

  /** A diagram with nothing hidden, nothing collapsed, no selected renderer, and the automatic layout. */
  val automatic: ThumbnailView = ThumbnailView(DiagramMetadata(), DiagramLayout.empty)

  def of(metadata: DiagramMetadata, layout: DiagramLayout, file: Option[String] = None): ThumbnailView =
    ThumbnailView(
      DiagramMetadata(hiddenElements = metadata.hiddenElements, collapsedGroups = metadata.collapsedGroups, renderer = metadata.renderer),
      if layout.isManual then layout else DiagramLayout.empty,
      file
    )

/** Renders diagram text (DOT or Mermaid) into a standalone SVG element for library/thumbnail previews.
  *
  * This is a stateless batch renderer: it does not use the reactive editing pipeline in [[InternalPhases]]. It uses
  * the same pure rules and functions as that pipeline, so a card draws as the view draws: `toVisibleGraph`,
  * [[DiagramLanguages.rendererFor]], [[MetadataOps.viewDiffersFromSource]], [[GraphvizRenderer.drawGraph]] and
  * [[MermaidRenderer.graphToSvg]].
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

  /** The SVG elements that put marks on the screen.
    *
    * The sheet behind a Mermaid drawing (MermaidBackend.addSheet) is not a
    * mark of the drawing. Thus an SVG with only a sheet holds no drawing.
    */
  private val DrawableSelector =
    s"path, rect:not(.${MermaidBackend.sheetClass}), circle, ellipse, line, polyline, polygon, text, image, use, foreignObject"

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

  /** The version of the drawing rule in each cache key. A key of an older rule (the source only) never matches, so
    * a card that an older build drew from its source draws again one time.
    */
  private val CacheVersion = "thumb-v2"

  /** The cache key of a thumbnail: each input that changes the drawing. A source has no NUL character, so each part
    * of the key stays apart.
    */
  private[graphexplorer] def cacheKeyOf(source: String, view: ThumbnailView = ThumbnailView.automatic): String =
    s"$CacheVersion\u0000${view.key}\u0000$source"

  /** What draws a thumbnail. */
  private[state] enum Drawing derives CanEqual:
    /** The renderer of the language draws the source text. */
    case Source

    /** Graphviz draws the visible graph ([[GraphvizRenderer.drawGraph]]), in its layout. */
    case GraphvizGraph

    /** Mermaid draws the visible graph ([[MermaidRenderer.graphToSvg]]). */
    case MermaidGraph

  /** The drawing of the view of a diagram, with the rules of the view: the renderer from
    * [[DiagramLanguages.rendererFor]], and the source or the visible graph as `MermaidRenderer.render` and
    * `GraphvizRenderer.render` decide.
    *
    * Graphviz always draws the visible graph. Mermaid draws its own source when the view does not differ from the
    * source ([[MetadataOps.viewDiffersFromSource]]), and else the visible graph. A source in a different language
    * than the renderer is always drawn from its graph.
    *
    * @param noGraph true for a kind of diagram with no graph (a Mermaid sequence diagram).
    */
  private[state] def drawingOf(
      languages: DiagramLanguages,
      format:    DiagramFormat,
      view:      ThumbnailView,
      noGraph:   Boolean
  ): Drawing =
    val renderer = languages.rendererFor(format, view.metadata.renderer, noGraph, view.layout.isManual)
    if noGraph then Drawing.Source
    else if renderer.format == DiagramFormat.DOT then Drawing.GraphvizGraph
    else if renderer.format != format || MetadataOps.viewDiffersFromSource(view.metadata) then Drawing.MermaidGraph
    else Drawing.Source

  /** Remove the hit areas of the canvas. A card does not select an element. */
  private def withoutHitAreas(svg: dom.svg.SVG): dom.svg.SVG =
    svg.querySelectorAll(s".${SelectableElement.hitAreaClass}").foreach(hit => hit.parentNode.removeChild(hit))
    svg

  /** The cache key of a drawing with the images `images` next to the file of the diagram. A key with no image is
    * `baseKey`. Each loaded image (its path, its URL and its size) is in the key, so a card that an image did not
    * load for draws again when the image loads.
    */
  private def keyWithImages(baseKey: String, images: LocalImages): String =
    if images.isEmpty then baseKey
    else
      val loaded = images.images.toVector.sortBy(_._1).map((src, l) => s"$src=${l.url}|${l.size.fold("")(z => s"${z.width}x${z.height}")}")
      s"$baseKey\u0000images:${loaded.mkString("\u0001")}"

  /** The disk key of the relative image paths of a source. The key differs from each key of a drawing. */
  private def sourcesKeyOf(baseKey: String): String = s"sources\u0000$baseKey"

  /** The relative image paths of each source that a card parsed in this session, by its base key. A card that knows
    * them asks the shell for the images, and it does not parse the source again.
    */
  private val knownSources = scala.collection.mutable.Map.empty[String, Set[String]]
  private val MaxKnownSources = 1000

  private def rememberSources(format: DiagramFormat, baseKey: String, sources: Set[String]): Unit =
    if knownSources.size >= MaxKnownSources then knownSources.clear()
    knownSources(baseKey) = sources
    ThumbnailDiskCache.put(format, sourcesKeyOf(baseKey), sources.toVector.sorted.mkString("\n"))

  /** The relative image paths of a source, when this session or the disk cache knows them. */
  private def storedSources(format: DiagramFormat, baseKey: String)(using ExecutionContext): Future[Option[Set[String]]] =
    knownSources.get(baseKey) match
      case Some(sources) => Future.successful(Some(sources))
      case None =>
        ThumbnailDiskCache
          .get(format, sourcesKeyOf(baseKey))
          .map(_.map: text =>
            val sources = text.split("\n").filter(_.nonEmpty).toSet
            knownSources(baseKey) = sources
            sources)

  /** Process diagram text (DOT or Mermaid) and return an SVG element.
    * Used for generating thumbnails on the library page.
    *
    * The card draws as the view of the diagram draws (ADR 0004). The view comes from the source and from `view`: the
    * hidden elements, the collapsed groups, the selected renderer, the layout of the manual mode, and the file. The
    * source gives the graph, and the card draws `toVisibleGraph` of it with the renderer of the view ([[drawingOf]]).
    * Thus the card has the theme of the view, and it does not show a hidden element. A kind of diagram with no graph
    * (a Mermaid sequence diagram) draws from its source. A Mermaid source with nothing hidden draws from its source
    * also, as in the view. The cache key holds each of these inputs ([[cacheKeyOf]]).
    *
    * On the desktop, an image with a path relative to the diagram comes from the shell (`DesktopFiles.localImage`),
    * as in the view (`LocalFileOps.followLocalImages`). The card waits for the answers of the shell before it draws,
    * so that no cache keeps a drawing with no image for an image that loads. An image that the shell refuses draws as
    * the view draws it then. The key of the drawing holds the loaded images ([[keyWithImages]]).
    */
  def processDotText(
      graphviz:          Graphviz,
      dot:              DotText,
      view:             ThumbnailView = ThumbnailView.automatic,
      telemetryContext: Seq[(String, Any)] = Nil
  )(using ExecutionContext): Signal[ReactiveSvgElement[SVG]] =
    val format    = DiagramFormat.detect(dot.value)
    val baseKey   = cacheKeyOf(dot.value, view)
    lazy val languages = DefaultDiagramLanguages(graphviz)
    lazy val extractor = languages.extractor(format)
    // The images next to a file come from the shell. A browser diagram has no file.
    val imageFolder = view.file.filter(_ => DesktopIpc.available)

    // Each event from here has the context of the caller. All events go
    // through this one function, so no call site can lose the diagram id.
    def log(name: String, extra: (String, Any)*): Unit =
      Telemetry.log(name, (telemetryContext ++ extra)*)

    def cacheFields = Seq("format" -> format.toString, "cacheSize" -> ThumbnailSvgCache.size)

    // Adopt a parsed SVG (from a cache, or fresh off a render) as this card's
    // element, refilling the in-memory cache on the way so a card scrolled
    // past twice pays the lookup only once.
    def adopt(key: String)(proto: dom.svg.SVG): ReactiveSvgElement[SVG] =
      ThumbnailSvgCache.put(format, key, proto)
      log("thumb.cache.store", cacheFields*)
      ThumbnailSvgCache.cloneSvg(proto)

    // The images of `sources` next to the file of the diagram, as the view asks for them. A refused image is not in
    // the result, and it draws as the view draws it then.
    def imagesOf(sources: Set[String]): Future[LocalImages] =
      imageFolder.filter(_ => sources.nonEmpty) match
        case None => Future.successful(LocalImages.empty)
        case Some(diagram) =>
          Future
            .traverse(sources.toVector.sorted): src =>
              DesktopFiles
                .localImage(diagram, src)
                .map(loaded => Option(src -> loaded))
                .recover:
                  case NonFatal(error) =>
                    log("thumb.image.refused", "src" -> src, "message" -> Option(error.getMessage).getOrElse(""))
                    None
            .map(found => LocalImages(found.flatten.toMap))

    // The parse of DOT lays the graph out, so it waits for idle time as each render does.
    def parseGraph(): Future[Try[ViewerGraph]] =
      onIdle(() => extractor.textToGraph(dot.value)).flatMap(identity).transform(result => Success(result))

    // ONE svg-only render (`textToSvgOnly`) straight from the source text.
    // Only a source that the card cannot parse comes here: the DOT parse
    // failed, and this render fails too. Runs on idle (see [[onIdle]]) so
    // a card revealed mid-scroll does not spend the scroll's own frames
    // laying itself out. A failure propagates, reaching the signal's error
    // channel exactly as Signal.fromTry did.
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
    // The view draws the source with the URL of each loaded image
    // (`LocalImages.mermaidText`), and the renderer puts a placeholder in
    // place of each other relative image.
    def renderMermaid(startedAt: Double, images: LocalImages): Future[String] =
      log("thumb.mermaid.start", "sourceChars" -> dot.value.length)
      val renderer = MermaidRenderer()
      onIdle(() => renderer.textToSvg(images.mermaidText(dot.value)))
        .flatMap(identity)
        .map: r =>
          log("thumb.mermaid.done", "dtMs" -> (Telemetry.nowMs() - startedAt), "ok" -> true)
          r.svg.ref.outerHTML

    def renderSource(startedAt: Double, images: LocalImages): Future[String] =
      format match
        case DiagramFormat.DOT     => renderDot(startedAt)
        case DiagramFormat.Mermaid => renderMermaid(startedAt, images)

    // The visible graph of the view, drawn by the renderer of the view. A render-only kind, or a source that does
    // not parse, draws from its source: the view draws such a source with the renderer of its language.
    def renderGraph(startedAt: Double, parsed: Try[ViewerGraph], images: LocalImages): Future[String] =
      parsed match
        case Failure(_) => renderSource(startedAt, images)
        case Success(graph) =>
          val visible = graph.toVisibleGraph(MetadataOps.hiddenIn(view.metadata), MetadataOps.collapsedIn(view.metadata))
          drawingOf(languages, format, view, noGraph = false) match
            case Drawing.Source => renderSource(startedAt, images)
            case Drawing.GraphvizGraph =>
              onIdle { () =>
                // As the view: the layout keeps the nodes of the full graph, and the bends come from the full graph.
                val active = view.layout.restrictedTo(graph.nodeIds.map(_.value))
                val drawn  = GraphvizRenderer.drawGraph(graphviz, visible, active, FreeLayout.bendsByArrow(graph, active), images)
                log("thumb.graphviz.total", "dtMs" -> (Telemetry.nowMs() - startedAt), "ok" -> drawn.isSuccess)
                withoutHitAreas(drawn.get.svg.ref).outerHTML
              }
            case Drawing.MermaidGraph =>
              onIdle(() => MermaidRenderer().graphToSvg(visible, images))
                .flatMap(identity)
                .map: drawn =>
                  log("thumb.mermaidGraph.total", "dtMs" -> (Telemetry.nowMs() - startedAt), "ok" -> true)
                  withoutHitAreas(drawn.svg.ref).outerHTML

    val startedAt = Telemetry.nowMs()

    // Render and parse the result. A result with no drawing is a failed
    // render: the disk cache does not get it, and the card shows what it
    // shows for other failures of this format. Only a result with a
    // drawing goes into the disk cache. A view that draws the source with
    // or with no graph needs no parse. `parsed` is a parse that the card
    // made already for the paths of the images.
    def renderFresh(key: String, images: LocalImages, parsed: Option[Try[ViewerGraph]]): Future[dom.svg.SVG] =
      val rendered =
        if drawingOf(languages, format, view, noGraph = false) == Drawing.Source then renderSource(startedAt, images)
        else parsed.fold(parseGraph())(Future.successful).flatMap(renderGraph(startedAt, _, images))
      rendered.map: svgHtml =>
        val proto = parseSVG(svgHtml).ref
        if !hasDrawing(proto) then
          log("thumb.render.noDrawing", "format" -> format.toString, "bytes" -> svgHtml.length)
          throw NoDrawingException(format)
        ThumbnailDiskCache.put(format, key, svgHtml)
        proto

    // The persistent cache sits BETWEEN the in-memory one and the renderer:
    // a library seen before parses stored SVG and lays out nothing. A miss,
    // a failed open (private browsing), or a corrupt record all arrive here
    // as None and simply render — the store is an optimisation, never a
    // source of truth. A stored record that does not parse as SVG, or that
    // holds no drawing, is also a miss. Earlier versions stored SVGs with no
    // drawing, and such a record stays until its key changes. The fresh
    // render replaces the record.
    def fromDiskOrRender(key: String, images: LocalImages, parsed: Option[Try[ViewerGraph]]): Future[dom.svg.SVG] =
      ThumbnailDiskCache.get(format, key).flatMap:
        case Some(stored) =>
          Try(parseSVG(stored).ref).toOption.filter(hasDrawing) match
            case Some(proto) =>
              log("thumb.disk.hit", "format" -> format.toString, "bytes" -> stored.length)
              // A Mermaid record from before the sheet has no sheet. The
              // record stays until its key changes. addSheet adds no second
              // sheet to a record that has one. A drawing of Graphviz has no
              // sheet. Before a parse, the scan of the extractor tells a kind
              // with no graph, as in the view.
              val drawing = drawingOf(languages, format, view, noGraph = extractor.declaresRenderOnlyKind(dot.value))
              val byMermaid = drawing == Drawing.MermaidGraph || (drawing == Drawing.Source && format == DiagramFormat.Mermaid)
              if byMermaid then MermaidBackend.addSheet(proto, MermaidBackend.backgroundOf(dot.value))
              Future.successful(proto)
            case None =>
              log("thumb.disk.noDrawing", "format" -> format.toString, "bytes" -> stored.length)
              renderFresh(key, images, parsed)
        case None =>
          log("thumb.disk.miss", "format" -> format.toString)
          renderFresh(key, images, parsed)

    // The drawing for `images`: the in-memory cache, the disk cache, or a fresh render.
    def drawingWith(images: LocalImages, parsed: Option[Try[ViewerGraph]]): Future[ReactiveSvgElement[SVG]] =
      val key = keyWithImages(baseKey, images)
      ThumbnailSvgCache.get(format, key) match
        case Some(proto) =>
          log("thumb.cache.hit", cacheFields*)
          Future.successful(ThumbnailSvgCache.cloneSvg(proto))
        case None => fromDiskOrRender(key, images, parsed).map(adopt(key))

    // A file on the desktop: the relative image paths come first, from this
    // session, from the disk cache, or else from a parse. Then the shell
    // gives the images, and their answers give the key of the drawing.
    def desktopDrawing(): Future[ReactiveSvgElement[SVG]] =
      storedSources(format, baseKey).flatMap:
        case Some(sources) => imagesOf(sources).flatMap(drawingWith(_, None))
        case None =>
          parseGraph().flatMap: parsed =>
            val sources = parsed.toOption.fold(Set.empty[String])(LocalImages.relativeSources)
            rememberSources(format, baseKey, sources)
            imagesOf(sources).flatMap(drawingWith(_, Some(parsed)))

    // With no image to ask for, the in-memory cache answers at once.
    val noImages = imageFolder.isEmpty || knownSources.get(baseKey).exists(_.isEmpty)
    ThumbnailSvgCache.get(format, baseKey).filter(_ => noImages) match
      case Some(proto) =>
        log("thumb.cache.hit", cacheFields*)
        Signal.fromValue(ThumbnailSvgCache.cloneSvg(proto))

      case None =>
        log("thumb.cache.miss", cacheFields*)
        val drawn = if imageFolder.isEmpty then drawingWith(LocalImages.empty, None) else desktopDrawing()

        // A Mermaid failure lands on the "No preview" placeholder rather than in
        // Airstream's error channel, which used to raise a global error per card
        // mount. DOT keeps propagating: a DOT thumbnail that cannot render is a
        // real parse failure worth surfacing. Until either resolves, the card
        // shows a skeleton — the pending state is the starting value, so it can
        // no longer be confused with a failed render.
        val safe = format match
          case DiagramFormat.Mermaid => drawn.recover { case _ => emptySvg }
          case DiagramFormat.DOT     => drawn

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
