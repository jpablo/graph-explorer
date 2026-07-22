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
import org.scalajs.dom.svg.SVG

import scala.concurrent.ExecutionContext

/** Renders diagram text (DOT or Mermaid) into a standalone SVG element for library/thumbnail previews.
  *
  * This is a stateless batch renderer with no relation to the reactive editing pipeline in
  * [[InternalPhases]]. It intentionally keeps its own backend knowledge because it is a one-shot render,
  * not a live component.
  */
object ThumbnailRenderer:
  /** Process diagram text (DOT or Mermaid) and return an SVG element.
    * Used for generating thumbnails on the library page.
    */
  def processDotText(
      graphviz:          Graphviz,
      dot:              DotText,
      telemetryContext: Seq[(String, Any)] = Nil
  )(using ExecutionContext): Signal[ReactiveSvgElement[SVG]] =
    val format = DiagramFormat.detect(dot.value)
    ThumbnailSvgCache.get(format, dot.value) match
      case Some(proto) =>
        Telemetry.log(
          "thumb.cache.hit",
          (telemetryContext ++ Seq(
            "format"    -> format.toString,
            "cacheSize" -> ThumbnailSvgCache.size
          ))*
        )
        Signal.fromValue(ThumbnailSvgCache.cloneSvg(proto))

      case None =>
        Telemetry.log(
          "thumb.cache.miss",
          (telemetryContext ++ Seq(
            "format"    -> format.toString,
            "cacheSize" -> ThumbnailSvgCache.size
          ))*
        )

        format match
          case DiagramFormat.DOT =>
            // ONE svg-only render (`textToSvgOnly`) straight from the source
            // text. The old path laid the graph out TWICE — textToSimpleGraph
            // (full layout → dot_json → JSON parse), a ViewerGraph round-trip
            // re-serialized to DOT, then textToSvg (full layout again + json0
            // + edge positions a static thumbnail never reads). The 0ms delay
            // moves each card's render into its own macrotask so the browser
            // paints between cards instead of freezing on the whole batch.
            val startedAt = Telemetry.nowMs()
            EventStream
              .delay(0, dot)
              .map: _ =>
                val svgStartedAt = Telemetry.nowMs()
                val resultTry    = graphviz.textToSvgOnly(dot)
                Telemetry.log(
                  "thumb.dot.textToSvg",
                  (telemetryContext ++ Seq(
                    "dtMs" -> (Telemetry.nowMs() - svgStartedAt),
                    "ok"   -> resultTry.isSuccess
                  ))*
                )
                Telemetry.log(
                  "thumb.dot.total",
                  (telemetryContext ++ Seq(
                    "dtMs" -> (Telemetry.nowMs() - startedAt),
                    "ok"   -> resultTry.isSuccess
                  ))*
                )
                resultTry.foreach: proto =>
                  ThumbnailSvgCache.put(format, dot.value, proto)
                  Telemetry.log(
                    "thumb.cache.store",
                    (telemetryContext ++ Seq(
                      "format"    -> format.toString,
                      "cacheSize" -> ThumbnailSvgCache.size
                    ))*
                  )
                ThumbnailSvgCache.cloneSvg(resultTry.get) // failure → error channel, as Signal.fromTry did
              .toSignal(svg.svg()) // empty-svg placeholder until the deferred render lands

          case DiagramFormat.Mermaid =>
            // Mermaid format - use MermaidBackend (asynchronous)
            // Render to a string, then parse into a fresh element so SPA re-mounts don't reuse DOM nodes
            val startedAt = Telemetry.nowMs()
            Telemetry.log(
              "thumb.mermaid.start",
              (telemetryContext ++ Seq("sourceChars" -> dot.value.length))*
            )
            val backend = MermaidBackend()
            // recover: a render failure must land on the emptySvg placeholder below, not in
            // Airstream's error channel (which raised a global error per card mount).
            val svgHtmlFuture: scala.concurrent.Future[Option[String]] =
              backend
                .textToSvg(dot.value)
                .map(r => Option(r.svg.ref.outerHTML))
                .recover { case _ => None }
            Signal
              .fromFuture(svgHtmlFuture)
              .map(_.flatten)
              .map: (svgHtmlOpt: Option[String]) =>
                svgHtmlOpt match
                  case Some(svgHtml) =>
                    Telemetry.log(
                      "thumb.mermaid.done",
                      (telemetryContext ++ Seq(
                        "dtMs" -> (Telemetry.nowMs() - startedAt),
                        "ok"   -> true
                      ))*
                    )
                    val proto = parseSVG(svgHtml).ref
                    ThumbnailSvgCache.put(format, dot.value, proto)
                    Telemetry.log(
                      "thumb.cache.store",
                      (telemetryContext ++ Seq(
                        "format"    -> format.toString,
                        "cacheSize" -> ThumbnailSvgCache.size
                      ))*
                    )
                    ThumbnailSvgCache.cloneSvg(proto)
                  case None =>
                    // pending OR failed: show the "No preview" placeholder
                    emptySvg

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

end ThumbnailRenderer
