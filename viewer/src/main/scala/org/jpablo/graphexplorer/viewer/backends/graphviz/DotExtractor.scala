package org.jpablo.graphexplorer.viewer.backends.graphviz

import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, DiagramLanguageInfo, Extractor}
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.concurrent.Future

/** The extractor that reads a DOT source.
  *
  * It uses the parser of the Graphviz class. That parser is synchronous, so the result is
  * wrapped in Future.fromTry.
  */
class DotExtractor(graphviz: Graphviz) extends Extractor:

  override def id: String            = "dot"
  override def format: DiagramFormat = DiagramFormat.DOT

  override def info: DiagramLanguageInfo = DiagramLanguageInfo(
    selectorLabel = "Graphviz (DOT)",
    editorPlaceholder = "DOT source",
    documentationUrl = "https://www.graphviz.org/documentation/",
    documentationTitle = "Visit the Graphviz documentation for more information"
  )

  override def textToGraph(text: String): Future[ViewerGraph] =
    Future.fromTry(graphviz.textToSimpleGraph(text).map(simplegraph.toViewerGraph))

  override def extractTitle(text: String): Option[String] = DotSourceScan.graphTitle(text)

  override def diagramKind(text: String): Option[String] = DotSourceScan.diagramKind(text)
