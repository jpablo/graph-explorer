package org.jpablo.graphexplorer.viewer.backends

/** The title that a source declares, for code outside the backends.
  *
  * The two scanners are `private[backends]`. gx-core uses this object to read
  * a title, for example to name a diagram in the migration. The viewer reads
  * the same titles through `extractTitle` of each backend.
  */
object SourceTitle:

  def of(format: DiagramFormat, text: String): Option[String] = format match
    case DiagramFormat.DOT     => graphviz.DotSourceScan.graphTitle(text)
    case DiagramFormat.Mermaid => mermaid.MermaidSourceScan.diagramTitle(text)
