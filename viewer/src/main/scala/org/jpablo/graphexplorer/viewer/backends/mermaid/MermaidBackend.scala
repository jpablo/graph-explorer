package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.StateDiagramHeader
import org.jpablo.graphexplorer.viewer.components.selection.{MermaidSelectionStrategy, SelectableElement}
import org.jpablo.graphexplorer.viewer.domUtils.querySelectorAllT

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import org.scalajs.dom
import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

/** What [[MermaidExtractor]] and [[MermaidRenderer]] share: the start of the
  * Mermaid.js library, the queue of its operations, and the changes to the SVG
  * that it draws.
  */
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

  /** Mermaid's failure message, with our own diagnosis in front of it when the
    * source contains something we recognise. Mermaid reports the token the
    * grammar choked on and the line it choked at, which for a reserved
    * participant name is neither the word the user wrote nor the line they wrote
    * it on — so the raw message sends you to the wrong place. Keep it anyway:
    * when we have nothing to add, it is all there is.
    */
  private[mermaid] def explain(phase: String, error: Any, source: String): String =
    val raw = s"Mermaid $phase failed: $error"
    MermaidSourceScan.explainParseFailure(source).fold(raw)(hint => s"$hint\n\n$raw")

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
          suppressErrorRendering = true,
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

  private def withTimeout[A](f: Future[A])(using ExecutionContext): Future[A] =
    val p = Promise[A]()
    val handle = js.timers.setTimeout(OperationTimeoutMs) {
      p.tryFailure(new RuntimeException(s"[mermaid] operation did not settle within ${OperationTimeoutMs}ms"))
    }
    f.onComplete { result =>
      js.timers.clearTimeout(handle)
      p.tryComplete(result)
    }
    p.future

  /** Serialize Mermaid operations so parse/render don't race during lazy diagram registration. */
  private[mermaid] def enqueue[A](op: => Future[A])(using ExecutionContext): Future[A] = synchronized {
    val previous = operationChain.recover { case _ => () }
    val next = previous.flatMap(_ => withTimeout(Try(op).fold(Future.failed, identity)))
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

  /** The type that Mermaid gives to a `stateDiagram-v2` diagram. A
    * `stateDiagram` header (no `-v2`) gives the type `state`, which has an older
    * renderer and other DOM ids. That kind stays render-only.
    */
  private[mermaid] val StateDiagramType = "stateDiagram"

  private[mermaid] def renderOnlyDetails(diagramType: String): String =
    if diagramType == "state" then
      "“stateDiagram” diagrams are render-only. To select and edit the states on the canvas, " +
        s"change the header to `$StateDiagramHeader`."
    else
      s"“$diagramType” diagrams are render-only: the canvas draws and live-updates from the source, " +
        s"but selection and editing are available for flowcharts and state diagrams (`$StateDiagramHeader`) only."

  /** The state diagram of a parsed diagram, when it is a `stateDiagram-v2`.
    *
    * It reads the layout data of Mermaid's StateDB, not the parsed statements:
    * the layout data has the nested states, and it is what the renderer draws.
    */
  private[mermaid] def readStateDiagram(text: String, diagram: Diagram): Option[MermaidStateGraph] =
    diagram.db.toOption.filter(_ => diagram.`type` == StateDiagramType).map { yy =>
      val db   = yy.asInstanceOf[StateDiagramDB]
      val data = db.getData()
      val nodes = data.nodes.toSeq.map { n =>
        MermaidStateGraph.LayoutNode(
          id = n.id,
          label = n.label.toOption.map(stateLabel).getOrElse(n.id),
          descriptions = n.description.toOption.map(_.toList).getOrElse(Nil),
          shape = n.shape.getOrElse("rect"),
          parentId = n.parentId.toOption.filter(_.nonEmpty),
          isGroup = n.isGroup.getOrElse(false),
          domId = n.domId.toOption,
          cssClasses = n.cssClasses.getOrElse(""),
          cssStyles = n.cssStyles.toOption.map(_.toList).getOrElse(Nil)
        )
      }
      val edges = data.edges.toSeq.map { e =>
        MermaidStateGraph.LayoutEdge(e.id, e.start, e.end, e.label.getOrElse(""), e.classes.getOrElse(""))
      }
      val classDefs = jsMapToDict(db.getClasses()).toSeq.map { (name, classDef) =>
        name -> classDef.styles.toOption.map(_.toList).getOrElse(Nil)
      }
      MermaidStateGraph.fromLayout(
        nodes = nodes,
        edges = edges,
        direction = stateRootDirection(db),
        title = db.getDiagramTitle().toOption.filter(_.nonEmpty).orElse(MermaidSourceScan.frontmatterTitle(text)),
        classDefs = classDefs
      )
    }

  /** A layout label is a string. Before the parse ends it can be an array of
    * descriptions, and then the first one is the label.
    */
  private def stateLabel(label: js.Any): String =
    if js.Array.isArray(label) then label.asInstanceOf[js.Array[js.Any]].headOption.map(_.toString).getOrElse("")
    else label.toString

  /** The `direction` of the top level, when the source has one. The public
    * `getDirection` gives `TB` also when the source has no `direction` line.
    */
  private def stateRootDirection(db: StateDiagramDB): Option[String] =
    val dyn       = db.asInstanceOf[js.Dynamic]
    val statement = dyn.selectDynamic("getDirectionStatement")
    if js.typeOf(statement) != "function" then None
    else
      val found = dyn.getDirectionStatement()
      if js.isUndefined(found) || found == null then None
      else found.value.asInstanceOf[js.UndefOr[String]].toOption.filter(_.nonEmpty)

  /** Stamps the graph ids on the drawn states and transitions of a state
    * diagram, so that MermaidSelectionStrategy finds the elements of the graph.
    *
    * A state gets `data-node-id`. A transition path and its label get
    * `data-arrow-id`. A self-loop has no `edge<N>` path, and the strategy reads
    * its id from the DOM ids of its three paths.
    */
  private[mermaid] def stampStateElementIds(svg: dom.svg.SVG, diagram: MermaidStateGraph): Unit =
    val nodeIds  = diagram.nodeIdsByDomId
    val arrowIds = diagram.arrowIdsByDomId
    val arrowAttr = MermaidSelectionStrategy.arrowIdAttr
    svg.querySelectorAllT[dom.Element]("g.node").foreach { g =>
      nodeIds.get(g.id).foreach(id => g.setAttribute("data-node-id", id.value))
    }
    svg.querySelectorAllT[dom.Element]("path.transition").foreach { p =>
      arrowIds.get(p.id).foreach(id => p.setAttribute(arrowAttr, id.value))
    }
    svg.querySelectorAllT[dom.Element](".edgeLabel g.label[data-id]").foreach { label =>
      arrowIds.get(label.getAttribute("data-id")).foreach(id => label.setAttribute(arrowAttr, id.value))
    }


  /** Stamps the ids of the graph on the drawn elements of a flowchart that the
    * renderer drew from a graph, so that MermaidSelectionStrategy finds the
    * elements of the graph.
    *
    * A node gets `data-node-id`, a subgraph gets `data-group-id`, and the path
    * and the label of an arrow get `data-arrow-id`. An element with no name in
    * `ids` keeps the id that Mermaid gave it. The empty map changes nothing.
    */
  private val FlowchartNodeDomId = """flowchart-(.+)-\d+""".r

  private[mermaid] def stampDrawnIds(svg: dom.svg.SVG, ids: MermaidDrawnIds): Unit =
    val arrowAttr = MermaidSelectionStrategy.arrowIdAttr
    def attr(e: dom.Element, name: String): Option[String] = Option(e.getAttribute(name)).filter(_.nonEmpty)
    if ids.nodes.nonEmpty then
      svg.querySelectorAllT[dom.Element]("g.node").foreach { g =>
        // Mermaid writes the id of the node in the DOM id (`flowchart-<id>-<n>`), and not always in `data-id`
        val name = attr(g, "data-id").orElse(Option(g.id).collect { case FlowchartNodeDomId(id) => id })
        name.flatMap(ids.nodes.get).foreach(id => g.setAttribute("data-node-id", id.value))
      }
    if ids.groups.nonEmpty then
      svg.querySelectorAllT[dom.Element]("g.cluster").foreach { g =>
        attr(g, "data-id").orElse(Option(g.id).filter(_.nonEmpty)).flatMap(ids.groups.get)
          .foreach(id => g.setAttribute("data-group-id", id.value))
      }
    if ids.arrows.nonEmpty then
      svg.querySelectorAllT[dom.Element]("path.flowchart-link").foreach { p =>
        ids.arrows.get(p.id).foreach(id => p.setAttribute(arrowAttr, id.value))
      }
      svg.querySelectorAllT[dom.Element](".edgeLabel g.label[data-id]").foreach { label =>
        ids.arrows.get(label.getAttribute("data-id")).foreach(id => label.setAttribute(arrowAttr, id.value))
      }

  private[mermaid] def normalizeRenderedSvg(svg: dom.svg.SVG, defaultEdgeMarkerColor: Option[String]): Unit =
    fixViewBoxCase(svg)
    normalizeEdgeMarkers(svg, defaultEdgeMarkerColor)
    enforceInlineStylePrecedence(svg)
    applyNodeInlineTextStyles(svg)

  /** Mermaid's C4 renderer (v11.12) references `xlink:href` on the icon images embedded in
    * Person/System shapes but never declares `xmlns:xlink` on the svg root. Browsers tolerate
    * that in HTML, but [[parseSVG]] uses the strict XML parser, which rejects the WHOLE
    * document over the undeclared prefix — the diagram vanished without a trace. Declare the
    * namespace before parsing when it's referenced but missing. (C4 is the only bundled kind
    * with this defect — probed by XML-parsing every example kind's mermaid.render output.)
    */
  private[mermaid] def declareMissingXlinkNamespace(svgString: String): String =
    if svgString.contains("xlink:") && !svgString.contains("xmlns:xlink") then
      svgString.replaceFirst("<svg", """<svg xmlns:xlink="http://www.w3.org/1999/xlink"""")
    else svgString

  /** Mermaid's packet and radar renderers (v11.12) emit `viewbox` — lowercase — instead of
    * `viewBox`. SVG attribute names are case-sensitive, so the browser ignores it: the element
    * has no viewport, the canvas fit logic measures a sizeless drawing, and the diagram
    * disappears even though every shape is present with correct coordinates. Promote the value
    * to the real attribute. (All other diagram kinds emit `viewBox` correctly — verified by
    * rendering every bundled example kind through mermaid.render.)
    */
  private def fixViewBoxCase(svg: dom.svg.SVG): Unit =
    if !svg.hasAttribute("viewBox") && svg.hasAttribute("viewbox") then
      svg.setAttribute("viewBox", svg.getAttribute("viewbox"))
      svg.removeAttribute("viewbox")

  /** Mermaid edges are bare ~2px paths — a nearly unhittable click target. Insert an
    * invisible, wider clone underneath each edge path so clicks within a few px of the
    * curve still resolve to the edge. The clone keeps the `flowchart-link` class (so the
    * selection machinery treats it as an edge) but carries the original's DOM id in
    * `data-edge-id` (ids must stay unique) — MermaidSelectionStrategy reads that first,
    * so clone and original resolve to the same ArrowId. `stroke-dasharray:none` matters:
    * with `pointer-events: stroke`, a dashed clone would only hit-test on the dashes.
    *
    * Edge LABELS need their own hit target: their text lives in a foreignObject, so a
    * click on the glyphs targets an XHTML element — which the selection machinery's
    * SVG-namespace filter drops, making the most prominent part of an edge select
    * nothing. Cover each label with an invisible SVG rect carrying the edge's id
    * (mermaid stamps it on `g.label` as data-id), so label clicks resolve like edge
    * clicks. Both kinds of helper share `hitAreaClass`, which keeps them out of
    * SelectableElement.findAll — the rendered path stays the canonical edge element.
    */
  private[mermaid] def addEdgeHitAreas(svg: dom.svg.SVG): Unit =
    svg.querySelectorAllT[dom.Element](MermaidSelectionStrategy.linkPathSelector).foreach { p =>
      val hit = SelectableElement.hitHaloClone(p)
      hit.removeAttribute("marker-start")
      hit.removeAttribute("marker-end")
      hit.setAttribute("data-edge-id", p.id)
      // Keep the original class list too: MermaidSelectionStrategy's edge selector
      // matches on flowchart-link (or transition), and the halo must resolve like
      // its edge. cloneNode also keeps a stamped data-arrow-id.
      hit.setAttribute(
        "class",
        s"${Option(p.getAttribute("class")).getOrElse("")} ${SelectableElement.hitAreaClass}".trim
      )
      p.parentNode.insertBefore(hit, p)
    }
    svg.querySelectorAllT[dom.Element](".edgeLabel g.label[data-id]").foreach { label =>
      val edgeId = Option(label.getAttribute("data-id")).getOrElse("")
      // The foreignObject's width/height are set by mermaid's layout, so the rect can be
      // sized statically — getBBox is unavailable here (the SVG is not mounted yet).
      Option(label.querySelector("foreignObject")).foreach { fo =>
        val w = Option(fo.getAttribute("width")).getOrElse("0")
        val h = Option(fo.getAttribute("height")).getOrElse("0")
        if edgeId.nonEmpty && w.toDoubleOption.exists(_ > 0) then
          val hit = dom.document.createElementNS("http://www.w3.org/2000/svg", "rect")
          hit.setAttribute("width", w)
          hit.setAttribute("height", h)
          hit.setAttribute("data-edge-id", edgeId)
          // A state transition label has the graph id (stampStateElementIds).
          Option(label.getAttribute(MermaidSelectionStrategy.arrowIdAttr))
            .filter(_.nonEmpty)
            .foreach(hit.setAttribute(MermaidSelectionStrategy.arrowIdAttr, _))
          hit.setAttribute("class", s"${SelectableElement.edgeLabelHitClass} ${SelectableElement.hitAreaClass}")
          // appended AFTER the foreignObject so it paints (and hit-tests) on top of it
          hit.setAttribute("style", "fill:transparent;pointer-events:all;cursor:pointer")
          label.appendChild(hit)
      }
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
    Option(svg.querySelector(SelectableElement.idSelector(id)))

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

  /** A token that is new for each page load. Each render id holds it.
    *
    * Mermaid finds the element that it draws into by its id, and it looks in the
    * whole document. The library page shows thumbnails from the disk cache, and
    * those SVGs keep the ids of earlier sessions. A counter alone starts at 1 in
    * each session. Thus a stored SVG can have the id of a new render, and Mermaid
    * then removes that SVG or draws into it (see RenderIdClashSpec).
    *
    * The token is the start time of the page load in base 36, then a random part of
    * six base-36 digits. Two page loads get the same token only if they start in
    * the same millisecond and also get the same random part.
    */
  private val sessionToken: String =
    val time   = java.lang.Long.toString(js.Date.now().toLong, 36)
    val random = Integer.toString((js.Math.random() * Int.MaxValue).toInt, 36)
    time + "0" * (6 - random.length) + random

  /** The id of the next Mermaid render: `mermaid-render-<token>-<n>`.
    *
    * The token makes the id unique across page loads, and `n` makes it unique in
    * one page load. The id starts with a letter and holds only `[a-z0-9-]`. Thus
    * `getElementById` and the CSS `#id` selectors in the `<style>` of Mermaid
    * accept it. An earlier version wrote `mermaid-render-<n>`, with no token. That
    * id has one `-` less, so it never equals a new id.
    *
    * During a render, Mermaid adds a scratch element at the end of `<body>`. Its id
    * is `d` and then this id. style.scss neutralizes that element with
    * `body > div[id^="dmermaid-render-"]`. Keep the prefix `mermaid-render-`, or a
    * document scrollbar shows for one frame at each render.
    */
  def nextRenderId(): String =
    val n = renderCounter.incrementAndGet()
    s"mermaid-render-$sessionToken-$n"
