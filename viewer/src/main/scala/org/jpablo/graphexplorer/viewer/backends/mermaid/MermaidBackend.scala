package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.{DiagramBackend, DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.{ArrowPosition, Point}
import org.jpablo.graphexplorer.viewer.components.selection.MermaidSelectionStrategy
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
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
  // Ensure Mermaid is initialized (only happens once)
  MermaidBackend.ensureInitialized()

  override def format: DiagramFormat = DiagramFormat.Mermaid

  override def textToGraph(text: String): Future[ViewerGraph] =
    MermaidBackend.enqueue {
      parseMermaid(text).map(toViewerGraph)
    }

  override def textToSvg(text: String): Future[SvgWithPositions] =
    MermaidBackend.enqueue {
      val renderId = MermaidBackend.nextRenderId()
      dom.console.info(s"[mermaid] textToSvg start id=$renderId len=${text.length}")
      renderMermaid(renderId, text).map { svgString =>
        val svg = parseSVG(svgString)
        val edgePositions = extractEdgePositions(svg.ref)
        dom.console.info(s"[mermaid] textToSvg complete id=$renderId edges=${edgePositions.size}")
        SvgWithPositions(svg, edgePositions)
      }
    }

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
              diagram.parser.toOption
                .flatMap(parser => parser.yy.toOption)
                .orElse(diagram.db.toOption)
                .getOrElse(throw new Exception("Mermaid parser database missing"))
            val vertices  = convertVertices(yy.getVertices())
            val jsEdges   = yy.getEdges()
            val edges     = convertEdges(jsEdges)
            val subgraphs = convertSubgraphs(yy.getSubGraphs())
            val classDefs = convertClassDefs(yy.getClasses())
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
                vertices = vertices,
                edges = edges,
                subgraphs = subgraphs,
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

  // Check initialization flag from window object to survive HMR
  private val windowDyn = dom.window.asInstanceOf[js.Dynamic]

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
          flowchart = FlowchartConfig(useMaxWidth = false)
        )
      )
      setInitialized()
      dom.console.info("[mermaid] Mermaid.js initialized")

  /** Serialize Mermaid operations so parse/render don't race during lazy diagram registration. */
  private def enqueue[A](op: => Future[A])(using ExecutionContext): Future[A] = synchronized {
    val previous = operationChain.recover { case _ => () }
    val next = previous.flatMap(_ => Try(op).fold(Future.failed, identity))
    operationChain = next.map(_ => ()).recover { case _ => () }
    next
  }

  def nextRenderId(): String =
    val id = renderCounter.incrementAndGet()
    s"mermaid-render-$id"

  def apply()(using ExecutionContext): MermaidBackend = new MermaidBackend()
