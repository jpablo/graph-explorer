package org.jpablo.graphexplorer.viewer.backends

import com.raquo.airstream.core.Signal
import org.jpablo.graphexplorer.viewer.backends.graphviz.SvgWithPositions
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElementStrategy

import scala.concurrent.Future

/** The reactive text sources a renderer may choose to render from.
  *
  * @param visibleText
  *   The visible graph (hidden nodes removed, groups collapsed) serialized in the language of
  *   the renderer.
  * @param sourceText
  *   The raw editor source text, exactly as the user typed it.
  * @param viewDiffersFromSource
  *   True when the visible graph is not the source graph — elements hidden OR groups
  *   collapsed. Renderers that prefer rendering the raw source for fidelity (Mermaid) must
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

/** The part that draws the graph of a diagram (ADR 0002).
  *
  * A renderer draws text in one language: Graphviz draws DOT text, and Mermaid draws Mermaid
  * text. The [[Printer]] of that language makes the text from a graph. A renderer does not
  * parse a source into a graph, and it does not write a source: those are the work of an
  * [[Extractor]] and of a [[Printer]].
  *
  * The methods return Futures or Signals, because one renderer is synchronous (Graphviz) and
  * one is asynchronous (Mermaid, which uses Promises).
  */
trait Renderer:
  /** The language of the text that this renderer draws. */
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
    */
  def render(inputs: DiagramRenderInputs): Signal[Option[SvgWithPositions]]
