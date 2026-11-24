package org.jpablo.graphexplorer.viewer.backends.mermaid

import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramRenderInputs, RenderState, Renderer}
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{ArrowPosition, Point}
import org.jpablo.graphexplorer.viewer.components.selection.{MermaidSelectionStrategy, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.concurrent.{ExecutionContext, Future, Promise}
import org.scalajs.dom
import scala.util.Try

/** The renderer that draws Mermaid text with the Mermaid.js library.
  *
  * It draws each kind of Mermaid diagram, also the kinds that [[MermaidExtractor]]
  * cannot read into a graph.
  */
class MermaidRenderer(using ExecutionContext) extends Renderer:

  override def id: String = "mermaid"

  override def label: String = "Mermaid"

  override def format: DiagramFormat = DiagramFormat.Mermaid

  /** Draw a Mermaid source. The parse of a flowchart source gives the drawn ids of its arrows. */
  override def textToSvg(text: String): Future[SvgWithPositions] =
    draw(text, None)

  /** Draw the graph, and stamp the ids of the graph on the SVG.
    *
    * The Mermaid text of a graph does not always keep the ids of the graph
    * (see [[MermaidDrawnIds]]). A graph from a DOT source is the usual case.
    */
  private def graphToSvg(graph: ViewerGraph): Future[SvgWithPositions] =
    draw(MermaidPrinter.graphToText(graph, omitInternal = false), Some(mermaidDrawnIds(graph)))

  /** Draw `text`, and stamp the drawn ids on the SVG.
    *
    * With no `drawnIds`, the text is the source of the diagram. Then the parse
    * of a flowchart text gives the drawn ids of its arrows.
    */
  private def draw(text: String, drawnIds: Option[MermaidDrawnIds]): Future[SvgWithPositions] =
    MermaidBackend.ensureInitialized()
    MermaidBackend.enqueue {
      val renderId = MermaidBackend.nextRenderId()
      val defaultEdgeMarkerColor = MermaidBackend.extractDefaultEdgeMarkerColor(text)
      dom.console.info(s"[mermaid] textToSvg start id=$renderId len=${text.length}")
      for
        (stateDiagram, sourceIds) <- parseDrawnIds(text, readFlowchart = drawnIds.isEmpty)
        svgString                 <- renderMermaid(renderId, text)
      yield
        val svg = parseSVG(MermaidBackend.declareMissingXlinkNamespace(svgString))
        MermaidBackend.normalizeRenderedSvg(svg.ref, defaultEdgeMarkerColor)
        // Before extractEdgePositions, which reads the ids of the transitions.
        stateDiagram.foreach(MermaidBackend.stampStateElementIds(svg.ref, _))
        // Before extractEdgePositions also, which reads the ids of the arrows.
        MermaidBackend.stampDrawnIds(svg.ref, drawnIds.getOrElse(sourceIds))
        val edgePositions = extractEdgePositions(svg.ref)
        // After extractEdgePositions, so the id-less clones don't pollute the positions map
        MermaidBackend.addEdgeHitAreas(svg.ref)
        // The last step: the sheet covers the viewBox, and fixViewBoxCase
        // (in normalizeRenderedSvg) can set the viewBox.
        MermaidBackend.addSheet(svg.ref)
        dom.console.info(s"[mermaid] textToSvg complete id=$renderId edges=${edgePositions.size}")
        SvgWithPositions(svg, edgePositions)
    }

  override def selectionStrategy: SelectableElementStrategy = MermaidSelectionStrategy

  override def render(inputs: DiagramRenderInputs): Signal[RenderState] =
    // Render the raw source text for fidelity (the Mermaid round-trip is lossy) — but when
    // the view differs from the source (hidden elements, collapsed groups), render the
    // serialized visible graph instead, otherwise hide/show and collapse/expand operations
    // have no visual effect in Mermaid mode (while exports DO exclude them).
    // The visible graph is only SUBSCRIBED while the view differs: combining it in
    // unconditionally serialized the whole graph on every parse just to discard the text,
    // and its post-parse recompute echoed a second identical mermaid render per keystroke.
    // The .distinct collapses that echo.
    // Inner protocol: None = STILL RENDERING, Some(x) = settled (x is NoDrawing for
    // empty text or a failed render). Signal.fromFuture emits None until the
    // future completes — flattening that into the output used to blank the
    // canvas for every async gap, which unmounted the previous svg and starved
    // the layout-stability hooks (no connected old svg ⇒ no anchoring, no
    // transition; also the visible re-render blink). The scanLeft turns the
    // gap into Pending, which keeps the previous drawing, and honors every
    // settled value. Thus a view knows that a render waits, for example in the
    // queue of MermaidBackend, and it does not take the wait as a failure.
    inputs.viewDiffersFromSource
      .flatMapSwitch: differs =>
        if differs then inputs.visibleGraph.map(Left(_): Either[ViewerGraph, String])
        else inputs.sourceText.map(Right(_): Either[ViewerGraph, String])
      .distinct
      .flatMapSwitch:
        case Left(graph) =>
          Signal.fromFuture(settled("the visible graph", graphToSvg(graph)))
        case Right(mermaidText) =>
          if mermaidText.trim.isEmpty then
            Signal.fromValue(Some(RenderState.NoDrawing(None)): Option[RenderState])
          else
            val detected = DiagramFormat.detect(mermaidText)
            if detected != DiagramFormat.Mermaid then
              // The registry already routed here by the format TAG; detection is advisory
              // (its prefix list can lag new Mermaid diagram types), so warn but still try —
              // a genuine non-Mermaid text fails the render and recovers to NoDrawing below.
              dom.console.warn(s"[mermaid] Text does not look like Mermaid (detected: $detected); attempting render anyway")
            Signal.fromFuture(settled("the source", textToSvg(mermaidText)))
      .scanLeft((first: Option[RenderState]) => first.getOrElse(RenderState.Pending(None))):
        (last, next) => next.getOrElse(RenderState.Pending(last.drawing))

  /** The result of a render, with a failure as "no drawing" and its cause.
    *
    * This recover is the render pipeline's last stop: without the log, a failure
    * here is a silently blank canvas (that's how the C4 xlink bug hid).
    */
  private def settled(input: String, result: Future[SvgWithPositions]): Future[RenderState] =
    result.map(RenderState.Drawn(_)).recover { case e =>
      dom.console.error(s"[mermaid] render pipeline failed for $input: ${e.getMessage}")
      RenderState.NoDrawing(Option(e.getMessage))
    }

  /** The state diagram of `text` when it is one, and the drawn ids of the
    * arrows of `text` when it is a flowchart and `readFlowchart` is true.
    *
    * The SVG alone does not tell which arrow a drawn element is. Mermaid gives
    * a transition the DOM id `edge<N>`. It gives a flowchart arrow the DOM id
    * `L_<start>_<end>_<n>`, and an `_` in a node id makes this id ambiguous.
    * Thus the render parses the text too, and stamps the graph ids on the SVG.
    * A failed parse gives no ids, so that the render reports the error of
    * Mermaid.
    *
    * The ids come from the parse before the render starts, because the render
    * parses the text again.
    */
  private def parseDrawnIds(text: String, readFlowchart: Boolean): Future[(Option[MermaidStateGraph], MermaidDrawnIds)] =
    val none: (Option[MermaidStateGraph], MermaidDrawnIds) = (None, MermaidDrawnIds.empty)
    val kind        = MermaidSourceScan.diagramKind(text)
    val isState     = kind.contains("state")
    val isFlowchart = readFlowchart && kind.contains("flowchart")
    if !isState && !isFlowchart then Future.successful(none)
    else
      val promise = Promise[(Option[MermaidStateGraph], MermaidDrawnIds)]()
      MermaidJS.mermaidAPI
        .getDiagramFromText(text)
        .`then`[Unit](
          diagram =>
            promise.complete(Try(
              if isState then (MermaidBackend.readStateDiagram(text, diagram), MermaidDrawnIds.empty)
              else (None, MermaidBackend.sourceDrawnIds(diagram))
            ))
            (),
          (_: Any) => { promise.success(none); () }
        )
      promise.future.recover { case _ => none }

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
