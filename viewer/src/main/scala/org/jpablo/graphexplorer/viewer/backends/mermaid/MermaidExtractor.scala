package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramLanguageInfo, Extractor, RenderOnlyDiagram}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import org.scalajs.dom

/** The extractor that reads a Mermaid source.
  *
  * It uses the parser of the Mermaid.js library. It parses flowcharts and state
  * diagrams (`stateDiagram-v2`) into a graph, so that the canvas can select and
  * edit them. Each other kind is render-only: [[MermaidRenderer]] draws it, and
  * this extractor fails with [[RenderOnlyDiagram]].
  */
class MermaidExtractor(using ExecutionContext) extends Extractor:

  override def id: String            = "mermaid"
  override def format: DiagramFormat = DiagramFormat.Mermaid

  override def info: DiagramLanguageInfo = DiagramLanguageInfo(
    selectorLabel = "MermaidJS",
    editorPlaceholder = "Mermaid source",
    documentationUrl = "https://mermaid.js.org/intro/",
    documentationTitle = "Visit the Mermaid documentation for more information"
  )

  override def textToGraph(text: String): Future[ViewerGraph] =
    // Initialize Mermaid.js lazily on first use so merely constructing/listing this extractor is cheap.
    MermaidBackend.ensureInitialized()
    MermaidBackend.enqueue {
      parseMermaid(text)
    }

  override def extractTitle(text: String): Option[String] = MermaidSourceScan.diagramTitle(text)

  override def diagramKind(text: String): Option[String] = MermaidSourceScan.diagramKind(text)

  // The parse makes a graph of a flowchart and of a `stateDiagram-v2`. The scan cannot tell
  // a `stateDiagram` (render-only) from a `stateDiagram-v2`, so it does not count a state diagram.
  override def declaresRenderOnlyKind(text: String): Boolean =
    MermaidSourceScan.diagramKind(text).exists(kind => kind != "flowchart" && kind != "state")

  /** Parse Mermaid text asynchronously, converting the JS Promise to a Scala Future. */
  private def parseMermaid(text: String): Future[ViewerGraph] =
    val promise = Promise[ViewerGraph]()
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
          // The watchdog tracks whether getDiagramFromText SETTLED, so flip the flag on
          // entry: the render-only throw below settles the promise too, and flipping it
          // only on the success path made the watchdog cry "still pending" two seconds
          // after every render-only diagram had already been classified.
          completed = true
          try
            MermaidBackend.readStateDiagram(text, diagram) match
              case Some(stateDiagram) =>
                dom.console.info(
                  s"[mermaid] parsed state diagram states=${stateDiagram.states.size} transitions=${stateDiagram.transitions.size} composites=${stateDiagram.composites.size}"
                )
                promise.success(stateDiagramToViewerGraph(stateDiagram))
              case None =>
                promise.success(toViewerGraph(flowchartGraph(text, diagram)))
            ()
          catch case e: Throwable =>
            promise.failure(e)
            ()
        },
        { (error: Any) =>
          completed = true
          promise.failure(new Exception(MermaidBackend.explain("parsing", error, text)))
          ()
        }
      )

    promise.future

  /** The flowchart of a parsed diagram. Each kind other than a flowchart or a
    * state diagram is render-only.
    */
  private def flowchartGraph(text: String, diagram: Diagram): MermaidGraph =
    val yy =
      MermaidBackend
        .selectDiagramYY(diagram)
        .getOrElse(throw RenderOnlyDiagram(
          kind = diagram.`type`,
          details = MermaidBackend.renderOnlyDetails(diagram.`type`)
        ))
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
    // getDiagramFromText removes the frontmatter and does not keep its title,
    // so getDiagramTitle returns "". Read the frontmatter title from the source,
    // because a canvas edit prints the title again only from the graph.
    // The accTitle is not a title: it is text for screen readers, and Mermaid
    // does not draw it. A canvas edit prints the title as a visible title.
    val title = yy.getDiagramTitle().toOption.filter(_.nonEmpty)
      .orElse(MermaidSourceScan.frontmatterTitle(text))

    dom.console.info(
      s"[mermaid] parsed vertices=${vertices.size} edges=${edges.size} subgraphs=${subgraphs.size} classDefs=${classDefs.size} defaultEdgeStyle=${defaultEdgeStyle.nonEmpty} dir=${direction.getOrElse("")} title=${title.getOrElse("")}"
    )
    MermaidGraph(
      vertices = verticesWithSourceCoverage,
      edges = edgesWithSourceLabels,
      subgraphs = subgraphsWithSourceClasses,
      direction = direction,
      title = title,
      classDefs = classDefs,
      defaultEdgeStyle = defaultEdgeStyle,
      defaultEdgeInterpolate = defaultEdgeInterpolate,
      // Mermaid removes the directives before the parse, so read them from the source.
      initConfig = MermaidInitDirective.configOf(text)
    )

  // The convert functions decode the labels and the titles here, where they leave Mermaid: the parser results keep
  // the internal entity codes of Mermaid (see MermaidSourceScan.decodeEntityCodes).

  /** Convert Mermaid JS vertices to Scala model. */
  private def convertVertices(jsVertices: js.Dictionary[MermaidVertexJS]): Map[String, MermaidVertex] =
    jsVertices.map { case (id, v) =>
      id -> MermaidVertex(
        id = v.id,
        text = MermaidSourceScan.decodeEntityCodes(v.text),
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
        text = e.text.toOption.filter(_.nonEmpty).map(MermaidSourceScan.decodeEntityCodes),
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
        title = s.title.toOption.map(MermaidSourceScan.decodeEntityCodes),
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
