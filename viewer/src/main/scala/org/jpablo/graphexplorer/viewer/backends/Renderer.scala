package org.jpablo.graphexplorer.viewer.backends

import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElementStrategy
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

import scala.concurrent.Future

/** The reactive inputs a renderer may choose to render from.
  *
  * @param visibleGraph
  *   The visible graph (hidden nodes removed, groups collapsed). A renderer writes it as text
  *   in its own language, and it knows the name that this text gives to each element. Thus it
  *   can find the element of the graph for each element that it draws.
  * @param sourceText
  *   The raw editor source text, exactly as the user typed it.
  * @param viewDiffersFromSource
  *   True when the visible graph is not the source graph — elements hidden OR groups
  *   collapsed. Renderers that prefer rendering the raw source for fidelity (Mermaid) must
  *   switch to `visibleGraph` while this is true, otherwise hide/show and collapse/expand
  *   operations have no visual effect in that format. (This used to track hidden elements
  *   alone, which made collapsing a group a silent no-op in Mermaid mode.)
  *   It is also always true when the source is in a different language than the renderer
  *   draws (a DOT source with the Mermaid renderer). The renderer cannot draw that source, so
  *   `visibleGraph` is the only input that it can draw.
  */
final case class DiagramRenderInputs(
    visibleGraph: Signal[ViewerGraph],
    sourceText:   Signal[String],
    // No default on purpose: Mermaid's hide/show/collapse behavior depends on this signal,
    // and a defaulted-false caller would silently disable it.
    viewDiffersFromSource: Signal[Boolean]
)

/** The state of the drawing that a renderer gives for its inputs.
  *
  * A renderer tells when its render is not complete, and when its last render
  * gave no drawing. Thus a view does not guess the end of a render with a timer.
  */
enum RenderState:
  /** A render is not complete. `last` is the drawing of an earlier render. The
    * canvas keeps it until the new render ends, so that the canvas does not blank.
    */
  case Pending(last: Option[SvgWithPositions])

  /** The last render ended with this drawing. */
  case Drawn(svg: SvgWithPositions)

  /** The last render ended with no drawing. `cause` tells why, when the
    * renderer knows it. An empty source text gives no drawing and no cause.
    */
  case NoDrawing(cause: Option[String])

  /** The drawing to show now: the new drawing, or during a render, the drawing of an earlier render. */
  def drawing: Option[SvgWithPositions] = this match
    case Pending(last) => last
    case Drawn(svg)    => Some(svg)
    case NoDrawing(_)  => None

/** The part that draws the graph of a diagram (ADR 0002).
  *
  * A renderer draws a graph. To do so, it writes the graph as text in the language of its
  * engine: Graphviz draws DOT text, and Mermaid draws Mermaid text. It can also draw a source
  * in that language directly. A renderer does not parse a source into a graph, and it does not
  * write a source: those are the work of an [[Extractor]] and of a [[Printer]].
  *
  * The methods return Futures or Signals, because one renderer is synchronous (Graphviz) and
  * one is asynchronous (Mermaid, which uses Promises).
  */
trait Renderer:
  /** The name of this renderer in the metadata of a diagram (`DiagramMetadata.renderer`). It
    * is a stored value, so it must not change.
    */
  def id: String

  /** The name of this renderer in the UI. */
  def label: String

  /** The language of the text that this renderer draws. A source in this language is the only
    * source that it can draw directly.
    */
  def format: DiagramFormat

  /** Render diagram text to SVG with position data.
    *
    * @param text
    *   The diagram text, in the language of this renderer
    * @return
    *   A Future containing the SVG and edge positions
    */
  def textToSvg(text: String): Future[SvgWithPositions]

  /** Strategy for extracting element ids from the SVGs this renderer produces. */
  def selectionStrategy: SelectableElementStrategy

  /** The reactive rendering policy of this renderer for the viewer.
    *
    * Each renderer decides which input to render (the visible graph vs. the raw source), whether
    * rendering is synchronous or asynchronous, and any validation it needs. This keeps the
    * dispatch out of the consumer (`ViewerState`), which only asks the resolved renderer to render.
    *
    * The result is the [[RenderState]] of the last inputs: a render that is not complete, a
    * drawing, or no drawing with its cause.
    */
  def render(inputs: DiagramRenderInputs): Signal[RenderState]
