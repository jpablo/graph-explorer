package org.jpablo.graphexplorer.viewer.backends

import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.concurrent.Future

/** Presentation metadata for a diagram language, used to render the format selector UI without any
  * hardcoded per-format knowledge in the components.
  *
  * @param selectorLabel
  *   Label shown in the format dropdown, e.g. "Graphviz (DOT)".
  * @param editorPlaceholder
  *   Placeholder for the source editor, e.g. "DOT source".
  * @param documentationUrl
  *   Link to this language's documentation.
  * @param documentationTitle
  *   Tooltip/title for the documentation link.
  */
final case class DiagramLanguageInfo(
    selectorLabel:      String,
    editorPlaceholder:  String,
    documentationUrl:   String,
    documentationTitle: String
)

/** Failure of [[Extractor.textToGraph]] for a diagram kind that a renderer can DRAW but the
  * extractor cannot MODEL (its parse result exposes no graph accessors). The drawing on the
  * canvas is correct and live-updates from the source; only selection/editing is unavailable.
  * Consumers should present `getMessage` as an informational notice, not as a parse error.
  *
  * @param kind
  *   The diagram kind as reported by the extractor (e.g. "sequence").
  * @param details
  *   User-facing explanation, written by the extractor (it knows which kinds ARE modelable).
  */
final case class RenderOnlyDiagram(kind: String, details: String) extends Exception(details)

/** The part that makes the graph of a diagram from the raw content of a data source (ADR 0002).
  *
  * The two extractors of today read text in one language each: DOT and Mermaid. An extractor
  * does not draw, and it does not write: those are the work of a [[Renderer]] and of a
  * [[Printer]].
  *
  * `textToGraph` returns a Future, because one extractor is synchronous (DOT) and one is
  * asynchronous (Mermaid, which uses Promises).
  */
trait Extractor:
  /** The id of this extractor in the metadata of a diagram (`DiagramMetadata.extractor`). It
    * must not change, because each stored selection holds it.
    */
  def id: String

  /** The language of the content that this extractor reads. */
  def format: DiagramFormat

  /** Presentation metadata for the format selector UI (label, placeholder, documentation link). */
  def info: DiagramLanguageInfo

  /** Parse diagram text into a ViewerGraph.
    *
    * @param text
    *   The diagram source text
    * @return
    *   A Future containing the parsed ViewerGraph
    */
  def textToGraph(text: String): Future[ViewerGraph]

  /** The title that the diagram declares, when the source text has one (the Mermaid
    * frontmatter or `title` line, or the DOT graph-level `label`). The app shows it as the
    * name of a diagram that the person did not rename. It must be fast, because the app
    * calls it on each keystroke while the diagram has no name, and for each library card.
    */
  def extractTitle(text: String): Option[String]

  /** The kind of diagram the source declares (DOT: `digraph` vs `graph`; Mermaid:
    * flowchart, sequence, class, ...), for display beside the format badge. Same
    * cheapness contract as [[extractTitle]]: runs per library card.
    */
  def diagramKind(text: String): Option[String]
