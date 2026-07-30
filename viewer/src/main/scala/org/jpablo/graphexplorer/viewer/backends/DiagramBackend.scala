package org.jpablo.graphexplorer.viewer.backends

import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElementStrategy
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.concurrent.Future

/** The reactive text sources a backend may choose to render from.
  *
  * @param visibleText
  *   The visible graph (hidden nodes removed, groups collapsed) serialized in the current
  *   language.
  * @param sourceText
  *   The raw editor source text, exactly as the user typed it.
  * @param viewDiffersFromSource
  *   True when the visible graph is not the source graph — elements hidden OR groups
  *   collapsed. Backends that prefer rendering the raw source for fidelity (Mermaid) must
  *   switch to `visibleText` while this is true, otherwise hide/show and collapse/expand
  *   operations have no visual effect in that format. (This used to track hidden elements
  *   alone, which made collapsing a group a silent no-op in Mermaid mode.)
  */
final case class DiagramRenderInputs(
    visibleText: Signal[String],
    sourceText:  Signal[String],
    // No default on purpose: Mermaid's hide/show/collapse behavior depends on this signal,
    // and a defaulted-false caller would silently disable it.
    viewDiffersFromSource: Signal[Boolean]
)

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

/** Failure of [[DiagramBackend.textToGraph]] for a diagram kind the backend can RENDER but cannot
  * MODEL (its parse result exposes no graph accessors). The drawing on the canvas is correct and
  * live-updates from the source; only selection/editing is unavailable. Consumers should present
  * `getMessage` as an informational notice, not as a parse error.
  *
  * @param kind
  *   The diagram kind as reported by the backend (e.g. "sequence").
  * @param details
  *   User-facing explanation, written by the backend (it knows which kinds ARE modelable).
  */
final case class RenderOnlyDiagram(kind: String, details: String) extends Exception(details)

/** A backend that can parse diagram text, render it to SVG, and serialize a graph back to text.
  *
  * This trait abstracts over different diagram formats (DOT/Graphviz, Mermaid, etc.) to provide a unified interface for
  * the application. It is the single seam through which format-specific behavior is injected, so components such as
  * `InternalPhases` can depend on this abstraction instead of concrete backends.
  *
  * Parsing/rendering methods return Futures to support both synchronous backends (like Graphviz) and asynchronous
  * backends (like Mermaid which uses Promises).
  */
trait DiagramBackend:
  /** The format this backend handles. */
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

  /** Render diagram text to SVG with position data.
    *
    * @param text
    *   The diagram source text
    * @return
    *   A Future containing the SVG and edge positions
    */
  def textToSvg(text: String): Future[SvgWithPositions]

  /** Serialize a ViewerGraph back into this backend's diagram text (the inverse of [[textToGraph]]).
    *
    * @param graph
    *   The graph to serialize
    * @param omitInternal
    *   Drop internal-only attributes (e.g. generated ids). Backends without such attributes ignore it.
    */
  def graphToText(graph: ViewerGraph, omitInternal: Boolean): String

  /** The diagram's own declared title, when the source text carries one (Mermaid
    * frontmatter / `title` line, DOT graph-level `label`). Used as the display name for
    * projects the user has not renamed. Must be cheap: called per keystroke while a
    * project is still unnamed, and per library card.
    */
  def extractTitle(text: String): Option[String]

  /** The kind of diagram the source declares (DOT: `digraph` vs `graph`; Mermaid:
    * flowchart, sequence, class, ...), for display beside the format badge. Same
    * cheapness contract as [[extractTitle]]: runs per library card.
    */
  def diagramKind(text: String): Option[String]

  /** Strategy for extracting element ids from the SVGs this backend produces. */
  def selectionStrategy: SelectableElementStrategy

  /** The backend's reactive rendering policy for the viewer.
    *
    * Each backend decides which input to render (the visible graph vs. the raw source), whether
    * rendering is synchronous or asynchronous, and any validation it needs. This keeps the format
    * dispatch out of the consumer (`ViewerState`), which only asks the resolved backend to render.
    */
  def render(inputs: DiagramRenderInputs): Signal[Option[SvgWithPositions]]
