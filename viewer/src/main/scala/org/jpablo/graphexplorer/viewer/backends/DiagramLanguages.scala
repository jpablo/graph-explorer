package org.jpablo.graphexplorer.viewer.backends

import org.jpablo.graphexplorer.viewer.backends.graphviz.{Graphviz, GraphvizBackend}
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend

import scala.concurrent.ExecutionContext

/** Registry of the diagram languages/backends the application understands.
  *
  * This is the abstraction that decouples consumers (e.g.
  * [[org.jpablo.graphexplorer.viewer.state.InternalPhases]]) from concrete backend construction and from
  * `match`-on-format dispatch. A consumer depends on this trait alone; it never names `Graphviz`,
  * `MermaidBackend`, or a specific selection strategy.
  */
trait DiagramLanguages:
  /** The backend to fall back to before any format has been detected/selected. */
  def default: DiagramBackend

  /** Resolve the backend responsible for a given format. */
  def forFormat(format: DiagramFormat): DiagramBackend

  /** Resolve the backend for a piece of diagram text via format detection. */
  def detect(text: String): DiagramBackend =
    forFormat(DiagramFormat.detect(text))

/** Default registry wiring the concrete Graphviz and Mermaid backends.
  *
  * The Mermaid backend is created lazily so Mermaid.js is only initialized once a Mermaid diagram is
  * actually requested.
  */
class DefaultDiagramLanguages(graphviz: Graphviz)(using ExecutionContext) extends DiagramLanguages:
  private val graphvizBackend     = GraphvizBackend(graphviz)
  private lazy val mermaidBackend = MermaidBackend()

  override def default: DiagramBackend = graphvizBackend

  override def forFormat(format: DiagramFormat): DiagramBackend = format match
    case DiagramFormat.DOT     => graphvizBackend
    case DiagramFormat.Mermaid => mermaidBackend
