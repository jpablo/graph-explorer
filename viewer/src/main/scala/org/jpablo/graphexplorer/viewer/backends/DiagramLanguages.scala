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
  /** All available backends, in display order. Drives the format selector UI. */
  def all: List[DiagramBackend]

  /** Resolve the backend responsible for a given format. */
  def forFormat(format: DiagramFormat): DiagramBackend

/** Default registry wiring the concrete Graphviz and Mermaid backends.
  *
  * Backend construction is side-effect-free (Mermaid.js itself is initialized lazily inside the
  * backend on first parse/render), so building the registry is cheap.
  */
class DefaultDiagramLanguages(graphviz: Graphviz)(using ExecutionContext) extends DiagramLanguages:
  private val graphvizBackend = GraphvizBackend(graphviz)
  private val mermaidBackend  = MermaidBackend()

  // Display order: Mermaid first (the order the format selector always used).
  override def all: List[DiagramBackend] = List(mermaidBackend, graphvizBackend)

  override def forFormat(format: DiagramFormat): DiagramBackend = format match
    case DiagramFormat.DOT     => graphvizBackend
    case DiagramFormat.Mermaid => mermaidBackend
