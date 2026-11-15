package org.jpablo.graphexplorer.viewer.backends.mermaid

import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramRenderInputs, Renderer}
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{ArrowPosition, Point}
import org.jpablo.graphexplorer.viewer.components.selection.{MermaidSelectionStrategy, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG

import scala.concurrent.{ExecutionContext, Future, Promise}
import org.scalajs.dom
import scala.util.Try

/** The renderer that draws Mermaid text with the Mermaid.js library.
  *
  * It draws each kind of Mermaid diagram, also the kinds that [[MermaidExtractor]]
  * cannot read into a graph.
  */
class MermaidRenderer(using ExecutionContext) extends Renderer:

  override def format: DiagramFormat = DiagramFormat.Mermaid

  override def textToSvg(text: String): Future[SvgWithPositions] =
    MermaidBackend.ensureInitialized()
    MermaidBackend.enqueue {
      val renderId = MermaidBackend.nextRenderId()
      val defaultEdgeMarkerColor = MermaidBackend.extractDefaultEdgeMarkerColor(text)
      dom.console.info(s"[mermaid] textToSvg start id=$renderId len=${text.length}")
      for
        stateDiagram <- parseStateDiagram(text)
        svgString    <- renderMermaid(renderId, text)
      yield
        val svg = parseSVG(MermaidBackend.declareMissingXlinkNamespace(svgString))
        MermaidBackend.normalizeRenderedSvg(svg.ref, defaultEdgeMarkerColor)
        // Before extractEdgePositions, which reads the ids of the transitions.
        stateDiagram.foreach(MermaidBackend.stampStateElementIds(svg.ref, _))
        val edgePositions = extractEdgePositions(svg.ref)
        // After extractEdgePositions, so the id-less clones don't pollute the positions map
        MermaidBackend.addEdgeHitAreas(svg.ref)
        dom.console.info(s"[mermaid] textToSvg complete id=$renderId edges=${edgePositions.size}")
        SvgWithPositions(svg, edgePositions)
    }

  override def selectionStrategy: SelectableElementStrategy = MermaidSelectionStrategy

  override def render(inputs: DiagramRenderInputs): Signal[Option[SvgWithPositions]] =
    // Render the raw source text for fidelity (the Mermaid round-trip is lossy) — but when
    // the view differs from the source (hidden elements, collapsed groups), render the
    // serialized visible graph instead, otherwise hide/show and collapse/expand operations
    // have no visual effect in Mermaid mode (while exports DO exclude them).
    // visibleText is only SUBSCRIBED while the view differs: combining it in
    // unconditionally serialized the whole graph on every parse just to discard the text,
    // and its post-parse recompute echoed a second identical mermaid render per keystroke.
    // The .distinct collapses that echo.
    // Inner protocol: None = STILL RENDERING, Some(x) = settled (x is None for
    // empty text or a failed render). Signal.fromFuture emits None until the
    // future completes — flattening that into the output used to blank the
    // canvas for every async gap, which unmounted the previous svg and starved
    // the layout-stability hooks (no connected old svg ⇒ no anchoring, no
    // transition; also the visible re-render blink). The scanLeft holds the
    // previous drawing through the gap and honors every settled value.
    inputs.viewDiffersFromSource
      .flatMapSwitch(differs => if differs then inputs.visibleText else inputs.sourceText)
      .distinct
      .flatMapSwitch: mermaidText =>
        if mermaidText.trim.isEmpty then
          Signal.fromValue(Some(None): Option[Option[SvgWithPositions]])
        else
          val detected = DiagramFormat.detect(mermaidText)
          if detected != DiagramFormat.Mermaid then
            // The registry already routed here by the format TAG; detection is advisory
            // (its prefix list can lag new Mermaid diagram types), so warn but still try —
            // a genuine non-Mermaid text fails the render and recovers to None below.
            dom.console.warn(s"[mermaid] Text does not look like Mermaid (detected: $detected); attempting render anyway")
          val futureResult = textToSvg(mermaidText).map(Some(_)).recover { case e =>
            // This recover is the render pipeline's last stop: without the log, a failure
            // here is a silently blank canvas (that's how the C4 xlink bug hid).
            dom.console.error(s"[mermaid] render pipeline failed: ${e.getMessage}")
            None
          }
          Signal.fromFuture(futureResult)
      .scanLeft((first: Option[Option[SvgWithPositions]]) => first.getOrElse(None)):
        (last, next) => next.getOrElse(last)

  /** The state diagram of `text`, when it is one.
    *
    * The SVG alone does not tell which arrow a drawn transition is: Mermaid
    * gives it the DOM id `edge<N>`. Thus the render parses the text too, and
    * stamps the graph ids on the SVG. A failed parse gives None, so that the
    * render reports the error of Mermaid.
    */
  private def parseStateDiagram(text: String): Future[Option[MermaidStateGraph]] =
    if !MermaidSourceScan.diagramKind(text).contains("state") then Future.successful(None)
    else
      val promise = Promise[Option[MermaidStateGraph]]()
      MermaidJS.mermaidAPI
        .getDiagramFromText(text)
        .`then`[Unit](
          diagram => { promise.complete(Try(MermaidBackend.readStateDiagram(text, diagram))); () },
          (_: Any) => { promise.success(None); () }
        )
      promise.future.recover { case _ => None }

  /** Render Mermaid text to SVG asynchronously.
    */
  private def renderMermaid(id: String, text: String): Future[String] =
    val promise = Promise[String]()

    MermaidJS.render(id, text).`then`(
      { renderResult =>
        promise.success(renderResult.svg)
      },
      (error: Any) => promise.failure(new Exception(MermaidBackend.explain("rendering", error, text)))
    )

    promise.future

  private def extractEdgePositions(svg: dom.svg.SVG): Map[String, ArrowPosition] =
    val nodeList = svg.querySelectorAll(MermaidSelectionStrategy.edgeSelector)
    val positions = scala.collection.mutable.Map[String, ArrowPosition]()

    for i <- 0 until nodeList.length do
      val elem = nodeList.item(i).asInstanceOf[dom.Element]
      val pathOpt = elem match
        case path: dom.svg.Path => Some(path)
        case _ =>
          Option(elem.querySelector("path")).collect { case p: dom.svg.Path => p }

      pathOpt.foreach { path =>
        try
          val total = path.getTotalLength()
          val start = path.getPointAtLength(0)
          val end = path.getPointAtLength(total)
          val startPoint = Point(start.x, -start.y)
          val endPoint = Point(end.x, -end.y)
          // For Mermaid, the path element itself has the LS-/LE- classes needed for ID extraction.
          // Using the parent (edgePaths group) doesn't work because it lacks those classes.
          val arrowId = MermaidSelectionStrategy.extractArrowId(path).value
          positions.update(arrowId, ArrowPosition(startPoint, endPoint, controlPoints = Nil))
        catch
          case _: Throwable =>
            ()
      }

    positions.toMap
