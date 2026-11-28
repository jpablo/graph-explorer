package org.jpablo.graphexplorer.viewer.backends

import org.jpablo.graphexplorer.viewer.backends.graphviz.{DotExtractor, DotPrinter, Graphviz, GraphvizRenderer}
import org.jpablo.graphexplorer.viewer.backends.mermaid.{MermaidExtractor, MermaidPrinter, MermaidRenderer}

import scala.concurrent.ExecutionContext

/** Registry of the extractors, the printers, and the renderers that the application has
  * (ADR 0002).
  *
  * This is the abstraction that decouples consumers (e.g.
  * [[org.jpablo.graphexplorer.viewer.state.InternalPhases]]) from the construction of each part and
  * from `match`-on-format dispatch. A consumer depends on this trait alone; it never names
  * `Graphviz`, `MermaidRenderer`, or a specific selection strategy.
  *
  * The three parts are apart, so that a consumer asks only for the part that it uses. An
  * extractor and a printer belong to a language. A renderer does not: a person can select a
  * renderer for a diagram ([[rendererById]]), and [[renderer]] gives the renderer of a language
  * for a diagram with no selection.
  */
trait DiagramLanguages:
  /** All available extractors, in display order. Drives the format selector UI. */
  def extractors: List[Extractor]

  /** The extractor that reads a source in the given language. */
  def extractor(format: DiagramFormat): Extractor

  /** The extractor with the given id (`Extractor.id`). None for an id that this build does not
    * have.
    */
  def extractorById(id: String): Option[Extractor] = extractors.find(_.id == id)

  /** The printer that writes a graph as text in the given language. */
  def printer(format: DiagramFormat): Printer

  /** All available renderers, in display order. Drives the renderer selector UI. */
  def renderers: List[Renderer]

  /** The renderer of a source in the given language, when the person selected no renderer. It
    * draws text in that language.
    */
  def renderer(format: DiagramFormat): Renderer

  /** The renderer with the given id (`Renderer.id`). None for an id that this build does not
    * have.
    */
  def rendererById(id: String): Option[Renderer] = renderers.find(_.id == id)

  /** The renderer of a diagram in the manual mode, when the person selected no renderer (ADR 0004). It can pin a
    * node at a position.
    */
  def freeLayoutRenderer: Renderer = renderer(DiagramFormat.DOT)

  /** The renderer that draws a diagram (ADR 0002, ADR 0004). The view and the thumbnail of the library both use
    * this rule, so the two draw with the same renderer.
    *
    * A kind of diagram with no graph (a Mermaid sequence diagram) has only the renderer of its language, which draws
    * the source. Else the renderer that the person selected wins. Else a diagram in the manual mode goes to
    * [[freeLayoutRenderer]], and each other diagram to the renderer of its language. An id that this build does not
    * have counts as no selection.
    *
    * @param noGraph true for a kind of diagram with no graph.
    * @param freeLayout true for a diagram in the manual mode.
    */
  def rendererFor(format: DiagramFormat, selected: Option[String], noGraph: Boolean, freeLayout: Boolean): Renderer =
    val ofLanguage = renderer(format)
    if noGraph then ofLanguage
    else selected.flatMap(rendererById).getOrElse(if freeLayout then freeLayoutRenderer else ofLanguage)

/** Default registry wiring the concrete Graphviz and Mermaid parts.
  *
  * The construction of each part is side-effect-free (Mermaid.js itself is initialized lazily
  * on the first parse/render), so building the registry is cheap.
  */
class DefaultDiagramLanguages(graphviz: Graphviz)(using ExecutionContext) extends DiagramLanguages:
  private val dotExtractor     = DotExtractor(graphviz)
  private val graphvizRenderer = GraphvizRenderer(graphviz)
  private val mermaidExtractor = MermaidExtractor()
  private val mermaidRenderer  = MermaidRenderer()

  // Display order: Mermaid first (the order the format selector always used).
  override def extractors: List[Extractor] = List(mermaidExtractor, dotExtractor)

  override def extractor(format: DiagramFormat): Extractor = format match
    case DiagramFormat.DOT     => dotExtractor
    case DiagramFormat.Mermaid => mermaidExtractor

  override def printer(format: DiagramFormat): Printer = format match
    case DiagramFormat.DOT     => DotPrinter
    case DiagramFormat.Mermaid => MermaidPrinter

  override def renderers: List[Renderer] = List(graphvizRenderer, mermaidRenderer)

  override def renderer(format: DiagramFormat): Renderer = format match
    case DiagramFormat.DOT     => graphvizRenderer
    case DiagramFormat.Mermaid => mermaidRenderer
