package org.jpablo.graphexplorer.viewer.backends.mermaid

import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.backends.{DiagramBackend, DiagramFormat, DiagramLanguageInfo, DiagramRenderInputs}
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{ArrowPosition, Point}
import org.jpablo.graphexplorer.viewer.components.selection.{MermaidSelectionStrategy, SelectableElement, SelectableElementStrategy}
import org.jpablo.graphexplorer.viewer.domUtils.{parseSVG, querySelectorAllT}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import org.scalajs.dom
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

/** DiagramBackend implementation for Mermaid diagrams.
  *
  * This backend uses the Mermaid.js library to parse and render flowchart diagrams.
  */
class MermaidBackend(using ExecutionContext) extends DiagramBackend:

  override def format: DiagramFormat = DiagramFormat.Mermaid

  override def info: DiagramLanguageInfo = DiagramLanguageInfo(
    selectorLabel = "MermaidJS",
    editorPlaceholder = "Mermaid source",
    documentationUrl = "https://mermaid.js.org/intro/",
    documentationTitle = "Visit the Mermaid documentation for more information"
  )

  override def textToGraph(text: String): Future[ViewerGraph] =
    // Initialize Mermaid.js lazily on first use so merely constructing/listing this backend is cheap.
    MermaidBackend.ensureInitialized()
    MermaidBackend.enqueue {
      parseMermaid(text).map(toViewerGraph)
    }

  override def textToSvg(text: String): Future[SvgWithPositions] =
    MermaidBackend.ensureInitialized()
    MermaidBackend.enqueue {
      val renderId = MermaidBackend.nextRenderId()
      val defaultEdgeMarkerColor = MermaidBackend.extractDefaultEdgeMarkerColor(text)
      dom.console.info(s"[mermaid] textToSvg start id=$renderId len=${text.length}")
      renderMermaid(renderId, text).map { svgString =>
        val svg = parseSVG(svgString)
        MermaidBackend.normalizeRenderedSvg(svg.ref, defaultEdgeMarkerColor)
        val edgePositions = extractEdgePositions(svg.ref)
        // After extractEdgePositions, so the id-less clones don't pollute the positions map
        MermaidBackend.addEdgeHitAreas(svg.ref)
        dom.console.info(s"[mermaid] textToSvg complete id=$renderId edges=${edgePositions.size}")
        SvgWithPositions(svg, edgePositions)
      }
    }

  override def graphToText(graph: ViewerGraph, omitInternal: Boolean): String =
    // Mermaid has no internal-only attributes, so `omitInternal` does not apply.
    viewerGraphToMermaidText(graph)

  override def selectionStrategy: SelectableElementStrategy = MermaidSelectionStrategy

  override def render(inputs: DiagramRenderInputs): Signal[Option[SvgWithPositions]] =
    // Render the raw source text for fidelity (the Mermaid round-trip is lossy) — but when
    // elements are hidden, render the serialized visible graph instead, otherwise hide/show
    // operations have no visual effect in Mermaid mode (while exports DO exclude them).
    inputs.sourceText
      .combineWith(inputs.hasHiddenElements, inputs.visibleText)
      .flatMapSwitch: (sourceText, hasHidden, visibleText) =>
        val mermaidText = if hasHidden then visibleText else sourceText
        if mermaidText.trim.isEmpty then
          Signal.fromValue(None)
        else
          val detected = DiagramFormat.detect(mermaidText)
          if detected != DiagramFormat.Mermaid then
            // The registry already routed here by the format TAG; detection is advisory
            // (its prefix list can lag new Mermaid diagram types), so warn but still try —
            // a genuine non-Mermaid text fails the render and recovers to None below.
            dom.console.warn(s"[mermaid] Text does not look like Mermaid (detected: $detected); attempting render anyway")
          val futureResult = textToSvg(mermaidText).map(Some(_)).recover { case _ => None }
          Signal.fromFuture(futureResult).map(_.flatten)

  /** Parse Mermaid text asynchronously, converting the JS Promise to a Scala Future. */
  private def parseMermaid(text: String): Future[MermaidGraph] =
    val promise = Promise[MermaidGraph]()
    var completed = false

    dom.console.info(s"[mermaid] getDiagramFromText start len=${text.length}")
    js.timers.setTimeout(2000) {
      if !completed then
        dom.console.warn("[mermaid] getDiagramFromText still pending after 2s")
    }

    MermaidJS.mermaidAPI
      .getDiagramFromText(text)
      .`then`[Unit](
        { diagram =>
          try
            val yy =
              MermaidBackend
                .selectDiagramYY(diagram)
                .getOrElse(throw new Exception("Mermaid parser database missing"))
            val vertices  = convertVertices(MermaidBackend.jsMapToDict(yy.getVertices()))
            val jsEdges   = yy.getEdges()
            val edges     = convertEdges(jsEdges)
            val edgesWithSourceLabels =
              MermaidEdgeLabelFallback.withSourceEdgeLabels(text, edges)
            val subgraphs = convertSubgraphs(yy.getSubGraphs())
            val (verticesWithSourceClasses, subgraphsWithSourceClasses) =
              MermaidClassAssignmentFallback.withSourceClassAssignments(text, vertices, subgraphs)
            val verticesWithSourceLabels =
              MermaidVertexLabelFallback.withSourceVertexLabels(text, verticesWithSourceClasses)
            val verticesWithSourceCoverage =
              MermaidMissingVertexFallback.withSourceVertices(text, verticesWithSourceLabels, edgesWithSourceLabels, subgraphsWithSourceClasses)
            val classDefs = MermaidClassDefFallback.withSourceClassDefs(text, convertClassDefs(MermaidBackend.jsMapToDict(yy.getClasses())))
            val defaultEdgeStyle = jsEdges.defaultStyle.toOption.map(_.toList).getOrElse(Nil)
            val defaultEdgeInterpolate = jsEdges.defaultInterpolate.toOption
            val direction = yy.getDirection().toOption
            // Try getDiagramTitle first, fallback to getAccTitle
            val title = yy.getDiagramTitle().toOption.filter(_.nonEmpty)
              .orElse(yy.getAccTitle().toOption.filter(_.nonEmpty))

            dom.console.info(
              s"[mermaid] parsed vertices=${vertices.size} edges=${edges.size} subgraphs=${subgraphs.size} classDefs=${classDefs.size} defaultEdgeStyle=${defaultEdgeStyle.nonEmpty} dir=${direction.getOrElse("")} title=${title.getOrElse("")}"
            )
            completed = true
            promise.success(
              MermaidGraph(
                vertices = verticesWithSourceCoverage,
                edges = edgesWithSourceLabels,
                subgraphs = subgraphsWithSourceClasses,
                direction = direction,
                title = title,
                classDefs = classDefs,
                defaultEdgeStyle = defaultEdgeStyle,
                defaultEdgeInterpolate = defaultEdgeInterpolate
              )
            )
            ()
          catch case e: Throwable =>
            promise.failure(e)
            ()
        },
        { (error: Any) =>
          completed = true
          promise.failure(new Exception(s"Mermaid parsing failed: $error"))
          ()
        }
      )

    promise.future

  /** Render Mermaid text to SVG asynchronously.
    */
  private def renderMermaid(id: String, text: String): Future[String] =
    val promise = Promise[String]()

    MermaidJS.render(id, text).`then`(
      { renderResult =>
        promise.success(renderResult.svg)
      },
      (error: Any) =>
        promise.failure(new Exception(s"Mermaid rendering failed: $error"))
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

  /** Convert Mermaid JS vertices to Scala model. */
  private def convertVertices(jsVertices: js.Dictionary[MermaidVertexJS]): Map[String, MermaidVertex] =
    jsVertices.map { case (id, v) =>
      id -> MermaidVertex(
        id = v.id,
        text = v.text,
        labelType = v.labelType.toOption,
        domId = v.domId.toOption,
        styles = v.styles.toOption.map(_.toList).getOrElse(Nil),
        classes = v.classes.toOption.map(_.toList).getOrElse(Nil),
        shape = v.`type`.toOption
      )
    }.toMap

  /** Convert Mermaid JS edges to Scala model. */
  private def convertEdges(jsEdges: MermaidEdgesJS): List[MermaidEdge] =
    jsEdges.map { e =>
      MermaidEdge(
        start = e.start,
        end = e.end,
        edgeType = e.`type`.toOption,
        text = e.text.toOption.filter(_.nonEmpty),
        labelType = e.labelType.toOption,
        stroke = e.stroke.toOption,
        styles = e.style.toOption.map(_.toList).getOrElse(Nil),
        interpolate = e.interpolate.toOption
      )
    }.toList

  /** Convert Mermaid JS subgraphs to Scala model. */
  private def convertSubgraphs(jsSubgraphs: js.Array[MermaidSubgraphJS]): List[MermaidSubgraph] =
    jsSubgraphs.map { s =>
      MermaidSubgraph(
        id = s.id,
        title = s.title.toOption,
        nodes = s.nodes.toOption.map(_.toList).getOrElse(Nil),
        classes = s.classes.toOption.map(_.toList).getOrElse(Nil)
      )
    }.toList

  /** Convert Mermaid JS class definitions to Scala model. */
  private def convertClassDefs(jsClasses: js.Dictionary[MermaidClassDefJS]): Map[String, MermaidClassDef] =
    jsClasses.map { case (id, cd) =>
      id -> MermaidClassDef(
        styles = cd.styles.toOption.map(_.toList).getOrElse(Nil),
        textStyles = cd.textStyles.toOption.map(_.toList).getOrElse(Nil)
      )
    }.toMap

object MermaidBackend:
  private val renderCounter = new AtomicInteger(0)
  private var operationChain: Future[Unit] = Future.successful(())
  private val MarkerAttributes = List("marker-start", "marker-end")
  private val NodeTextStyleKeys = Set("color", "font-size", "font-family", "font-weight", "font-style")

  /** Convert a JS Map (Mermaid v11) or plain object (Mermaid v10) to js.Dictionary.
    *
    * Mermaid v11 changed `getVertices()` and `getClasses()` to return ES6 `Map` objects
    * instead of plain objects. `js.Dictionary` iterates via `Object.keys()` which yields
    * zero entries for Maps, so we must convert Maps with `Object.fromEntries()` first.
    */
  private[mermaid] def jsMapToDict[A](raw: js.Dictionary[A]): js.Dictionary[A] =
    val dyn = raw.asInstanceOf[js.Dynamic]
    if !js.isUndefined(dyn.selectDynamic("entries")) && js.typeOf(dyn.selectDynamic("entries")) == "function" then
      js.Dynamic.global.Object.fromEntries(dyn).asInstanceOf[js.Dictionary[A]]
    else
      raw

  // Check initialization flag from window object to survive HMR
  private def windowDyn = dom.window.asInstanceOf[js.Dynamic]

  private def isInitialized: Boolean =
    !js.isUndefined(windowDyn.__mermaidInitialized) &&
      windowDyn.__mermaidInitialized.asInstanceOf[Boolean]

  private def setInitialized(): Unit =
    windowDyn.__mermaidInitialized = true

  /** Initialize Mermaid.js only once, regardless of HMR reloads or multiple MermaidBackend instances. */
  private[mermaid] def ensureInitialized(): Unit =
    if !isInitialized then
      MermaidJS.initialize(
        MermaidConfig(
          startOnLoad = false,
          securityLevel = "loose",
          theme = "default",
          suppressErrors = true,
          flowchart = FlowchartConfig(htmlLabels = true, useMaxWidth = false)
        )
      )
      setInitialized()
      dom.console.info("[mermaid] Mermaid.js initialized")

  /** Mermaid promises occasionally never settle (the 2s "still pending" watchdog exists for a
    * reason). Without a timeout, one such promise wedged the operation chain forever — every
    * later parse/render queued behind it until page reload.
    */
  private val OperationTimeoutMs = 15000

  private def withTimeout[A](f: Future[A], label: String)(using ExecutionContext): Future[A] =
    val p = Promise[A]()
    val handle = js.timers.setTimeout(OperationTimeoutMs) {
      p.tryFailure(new RuntimeException(s"[mermaid] $label did not settle within ${OperationTimeoutMs}ms"))
    }
    f.onComplete { result =>
      js.timers.clearTimeout(handle)
      p.tryComplete(result)
    }
    p.future

  /** Serialize Mermaid operations so parse/render don't race during lazy diagram registration. */
  private def enqueue[A](op: => Future[A])(using ExecutionContext): Future[A] = synchronized {
    val previous = operationChain.recover { case _ => () }
    val next = previous.flatMap(_ => withTimeout(Try(op).fold(Future.failed, identity), "operation"))
    operationChain = next.map(_ => ()).recover { case _ => () }
    next
  }

  /** Mermaid v11 may expose `parser.yy` as an empty object while flowchart accessors live on `diagram.db`.
    * Guard the yy branch so we only use it when it actually has the expected methods.
    */
  private[mermaid] def hasFlowchartAccessors(yy: DiagramYY): Boolean =
    val dyn = yy.asInstanceOf[js.Dynamic]
    val vertices = dyn.selectDynamic("getVertices")
    !js.isUndefined(vertices) && js.typeOf(vertices) == "function"

  /** Mermaid v11 can expose both parser.yy and diagram.db, where parser.yy may be syntactically valid but semantically incomplete.
    * Prefer diagram.db when available to retain labels/classes/classDefs on write-back.
    */
  private[mermaid] def selectDiagramYY(diagram: Diagram): Option[DiagramYY] =
    diagram.db.toOption
      .filter(hasFlowchartAccessors)
      .orElse(
        diagram.parser.toOption
          .flatMap(parser => parser.yy.toOption.filter(hasFlowchartAccessors))
      )


  private[mermaid] def normalizeRenderedSvg(svg: dom.svg.SVG, defaultEdgeMarkerColor: Option[String]): Unit =
    normalizeEdgeMarkers(svg, defaultEdgeMarkerColor)
    enforceInlineStylePrecedence(svg)
    applyNodeInlineTextStyles(svg)

  /** Mermaid edges are bare ~2px paths — a nearly unhittable click target. Insert an
    * invisible, wider clone underneath each edge path so clicks within a few px of the
    * curve still resolve to the edge. The clone keeps the `flowchart-link` class (so the
    * selection machinery treats it as an edge) but carries the original's DOM id in
    * `data-edge-id` (ids must stay unique) — MermaidSelectionStrategy reads that first,
    * so clone and original resolve to the same ArrowId. `stroke-dasharray:none` matters:
    * with `pointer-events: stroke`, a dashed clone would only hit-test on the dashes.
    */
  private[mermaid] def addEdgeHitAreas(svg: dom.svg.SVG): Unit =
    svg.querySelectorAllT[dom.Element]("path.flowchart-link").foreach { p =>
      val hit = p.cloneNode(false).asInstanceOf[dom.Element]
      hit.removeAttribute("id")
      hit.removeAttribute("marker-start")
      hit.removeAttribute("marker-end")
      hit.setAttribute("data-edge-id", p.id)
      hit.setAttribute(
        "class",
        s"${Option(p.getAttribute("class")).getOrElse("")} ${SelectableElement.hitAreaClass}".trim
      )
      hit.setAttribute(
        "style",
        // non-scaling-stroke keeps the hit halo ~14 SCREEN px at any canvas zoom
        "fill:none;stroke:transparent;stroke-width:14px;stroke-dasharray:none;stroke-linecap:round;" +
          "pointer-events:stroke;vector-effect:non-scaling-stroke"
      )
      p.parentNode.insertBefore(hit, p)
    }

  /** Mermaid 10 emits class-level rules with `!important` for classDef, but inline `style` declarations without it.
    * In the browser, classDef can therefore override inline style. We promote inline declarations to `!important`
    * so element-level style behaves like Mermaid Live.
    */
  private def enforceInlineStylePrecedence(svg: dom.svg.SVG): Unit =
    val selector = "g.node > rect[style], g.cluster > rect[style], path.flowchart-link[style]"
    val nodes = svg.querySelectorAll(selector)
    for i <- 0 until nodes.length do
      Option(nodes.item(i)).foreach { elem =>
        Option(elem.getAttribute("style")).filter(_.nonEmpty).foreach { styleText =>
          val normalized = withImportantDeclarations(styleText)
          if normalized.nonEmpty then
            elem.setAttribute("style", normalized)
        }
      }

  private[mermaid] def withImportantDeclarations(styleText: String): String =
    styleText
      .split(';')
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map { declaration =>
        val separator = declaration.indexOf(':')
        if separator < 0 then declaration
        else
          val key   = declaration.substring(0, separator).trim
          val value = declaration.substring(separator + 1).trim
          val normalizedValue =
            if value.toLowerCase.contains("!important") then value
            else s"$value !important"
          s"$key:$normalizedValue"
      }
      .mkString(";")

  /** Mermaid 10 does not always project node inline `font-*`/`color` styles onto HTML label spans.
    * Apply the inline text declarations directly to node labels so they match Mermaid Live output.
    */
  private def applyNodeInlineTextStyles(svg: dom.svg.SVG): Unit =
    val classSpanFontSizes = extractClassSpanFontSizes(svg)
    val nodes = svg.querySelectorAll("g.node")
    for i <- 0 until nodes.length do
      Option(nodes.item(i)).foreach { node =>
        val baseFontPx = resolveNodeBaseFontSize(node, classSpanFontSizes)
        Option(node.querySelector("rect[style]"))
          .flatMap(rect => Option(rect.getAttribute("style")))
          .map(extractNodeInlineTextDeclarations)
          .filter(_.nonEmpty)
          .foreach { textDeclarations =>
            val targetFontPx = textDeclarations.find(_._1 == "font-size").flatMap { case (_, value) => parsePx(value) }
            val appendedStyle = textDeclarations.map { case (k, v) => s"$k:$v" }.mkString(";")
            val labels = node.querySelectorAll("g.label span, g.label text, g.label tspan")
            for j <- 0 until labels.length do
              Option(labels.item(j)).foreach { label =>
                val merged = Option(label.getAttribute("style")).filter(_.nonEmpty) match
                  case Some(existing) => s"$existing;$appendedStyle"
                  case None           => appendedStyle
                val normalized = withImportantDeclarations(merged)
                if normalized.nonEmpty then
                  label.setAttribute("style", normalized)
              }
            val scaleFactor = for
              from <- baseFontPx
              to   <- targetFontPx
              if from > 0 && to > from
            yield to / from
            scaleFactor.foreach(scale => expandNodeLabelBox(node, scale))
          }
      }

  private def extractClassSpanFontSizes(svg: dom.svg.SVG): Map[String, Double] =
    val cssText = Option(svg.querySelector("style")).flatMap(style => Option(style.textContent)).getOrElse("")
    val classRulePattern = raw"""\.([A-Za-z0-9_-]+)\s+span\s*\{([^}]*)\}""".r
    classRulePattern.findAllMatchIn(cssText).foldLeft(Map.empty[String, Double]) { (acc, m) =>
      val className = m.group(1)
      val body      = m.group(2)
      val fontSize = firstCssDeclarationValue(body, "font-size").flatMap(parsePx)
      fontSize match
        case Some(size) => acc + (className -> size)
        case None       => acc
    }

  private def resolveNodeBaseFontSize(node: dom.Element, classSpanFontSizes: Map[String, Double]): Option[Double] =
    val classes = Option(node.getAttribute("class")).toList.flatMap(_.split("\\s+")).filter(_.nonEmpty)
    val fromClasses = classes.foldLeft(Option.empty[Double]) { (current, cls) =>
      classSpanFontSizes.get(cls).orElse(current)
    }
    fromClasses.orElse(classSpanFontSizes.get("default")).orElse(Some(16.0))

  private[mermaid] def extractNodeInlineTextDeclarations(styleText: String): List[(String, String)] =
    val declarations = cssDeclarations(styleText)
    val textStyles = declarations.filter { case (key, _) => NodeTextStyleKeys.contains(key) }
    textStyles.find(_._1 == "color").map(_._2).fold(textStyles) { color =>
      val hasFill = textStyles.exists { case (key, _) => key == "fill" }
      if hasFill then textStyles else textStyles :+ ("fill" -> color)
    }

  private def normalizeEdgeMarkers(svg: dom.svg.SVG, defaultEdgeMarkerColor: Option[String]): Unit =
    val edges = svg.querySelectorAll("path.flowchart-link")
    for i <- 0 until edges.length do
      edges.item(i) match
        case path: dom.svg.Path =>
          MarkerAttributes.foreach { markerAttr =>
            Option(path.getAttribute(markerAttr))
              .flatMap(extractMarkerId)
              .flatMap { markerId =>
                markerColorForPath(path, defaultEdgeMarkerColor).flatMap { color =>
                  ensureColoredMarker(svg, markerId, color)
                }
              }
              .foreach { coloredMarkerId =>
                path.setAttribute(markerAttr, s"url(#$coloredMarkerId)")
              }
          }
        case _ => ()

  private def markerColorForPath(path: dom.svg.Path, defaultEdgeMarkerColor: Option[String]): Option[String] =
    defaultEdgeMarkerColor
      .orElse {
        Option(path.getAttribute("style"))
          .flatMap(style => firstCssDeclarationValue(style, "stroke"))
          .flatMap(normalizeCssValue)
      }
      .orElse(Option(path.getAttribute("stroke")).flatMap(normalizeCssValue))

  private[mermaid] def firstCssDeclarationValue(styleText: String, key: String): Option[String] =
    val target = key.trim.toLowerCase
    cssDeclarations(styleText).collectFirst { case (declKey, value) if declKey == target => value }

  private def cssDeclarations(styleText: String): List[(String, String)] =
    styleText
      .split(';')
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap { declaration =>
        val separator = declaration.indexOf(':')
        if separator < 0 then None
        else
          val declKey = declaration.substring(0, separator).trim.toLowerCase
          val value   = declaration.substring(separator + 1).trim
          if declKey.nonEmpty && value.nonEmpty then Some((declKey, value)) else None
      }
      .toList

  private def ensureColoredMarker(svg: dom.svg.SVG, markerId: String, color: String): Option[String] =
    val suffix          = markerColorSuffix(color)
    val coloredMarkerId = s"${markerId}__$suffix"
    queryById(svg, coloredMarkerId) match
      case Some(_) => Some(coloredMarkerId)
      case None =>
        queryById(svg, markerId).flatMap { originalMarker =>
          val markerParent = originalMarker.parentNode
          if markerParent == null then None
          else
            val clonedMarker = originalMarker.cloneNode(deep = true).asInstanceOf[dom.Element]
            clonedMarker.setAttribute("id", coloredMarkerId)
            colorizeMarkerContent(clonedMarker, color)
            markerParent.appendChild(clonedMarker)
            Some(coloredMarkerId)
        }

  private def colorizeMarkerContent(marker: dom.Element, color: String): Unit =
    val shapes = marker.querySelectorAll("path, polygon, polyline")
    if shapes.length == 0 then
      Option(marker.querySelector("*")).foreach(setMarkerShapeColor(_, color))
    else
      for i <- 0 until shapes.length do
        Option(shapes.item(i)).foreach(setMarkerShapeColor(_, color))

  private def setMarkerShapeColor(shape: dom.Element, color: String): Unit =
    shape.setAttribute("fill", color)
    shape.setAttribute("stroke", color)

  private def markerColorSuffix(color: String): String =
    normalizeCssValue(color)
      .map(_.stripPrefix("#"))
      .getOrElse("marker")
      .replaceAll("[^A-Za-z0-9_-]", "_")

  private def extractMarkerId(markerUrl: String): Option[String] =
    val prefix = "url(#"
    if markerUrl.startsWith(prefix) && markerUrl.endsWith(")") && markerUrl.length > prefix.length + 1 then
      Some(markerUrl.substring(prefix.length, markerUrl.length - 1))
    else None

  private def queryById(svg: dom.svg.SVG, id: String): Option[dom.Element] =
    val escaped = id.replace("\\", "\\\\").replace("\"", "\\\"")
    Option(svg.querySelector(s"""[id="$escaped"]"""))

  private def normalizeCssValue(value: String): Option[String] =
    Option(value.replace("!important", "").trim).filter(_.nonEmpty)

  private def parsePx(value: String): Option[Double] =
    normalizeCssValue(value).flatMap { cssValue =>
      val withoutPx = cssValue.stripSuffix("px")
      withoutPx.toDoubleOption
    }

  private def expandNodeLabelBox(node: dom.Element, scaleFactor: Double): Unit =
    if scaleFactor <= 1.0 then ()
    else
      Option(node.querySelector("rect.basic.label-container")).foreach { rect =>
        scaleCenteredAttribute(rect, sizeAttr = "width", centerAttr = "x", scaleFactor = scaleFactor)
      }
      Option(node.querySelector("g.label foreignObject")).foreach { foreignObject =>
        scaleCenteredAttribute(foreignObject, sizeAttr = "width", centerAttr = "x", scaleFactor = scaleFactor)
      }

  private def scaleCenteredAttribute(elem: dom.Element, sizeAttr: String, centerAttr: String, scaleFactor: Double): Unit =
    Option(elem.getAttribute(sizeAttr)).flatMap(_.toDoubleOption).foreach { currentSize =>
      val newSize = currentSize * scaleFactor
      elem.setAttribute(sizeAttr, newSize.toString)

      val currentCenter = Option(elem.getAttribute(centerAttr)).flatMap(_.toDoubleOption).getOrElse(0.0)
      val delta         = newSize - currentSize
      elem.setAttribute(centerAttr, (currentCenter - delta / 2.0).toString)
    }

  private[mermaid] def extractDefaultEdgeMarkerColor(text: String): Option[String] =
    text.linesIterator.foldLeft(Option.empty[String]) { (currentColor, rawLine) =>
      val line = rawLine.trim
      val prefix = "linkStyle default "
      if line.startsWith(prefix) then
        val declarations = MermaidStyleDeclarations.parse(line.drop(prefix.length))
        declarations
          .get("stroke")
          .orElse(declarations.get("color"))
          .flatMap(normalizeCssValue)
          .orElse(currentColor)
      else currentColor
    }

  def nextRenderId(): String =
    val id = renderCounter.incrementAndGet()
    s"mermaid-render-$id"

  def apply()(using ExecutionContext): MermaidBackend = new MermaidBackend()
