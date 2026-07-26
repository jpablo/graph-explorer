package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.state.ViewerState

/** Resolves the diagram-level attributes view for a given format.
  *
  * This keeps `RightPanel` (and any other consumer) free of per-format `match`es. The mapping lives
  * here, in the views layer, rather than on `DiagramBackend`: the views need the full `ViewerState`,
  * and pushing them onto the backend would make the backend layer depend on the orchestrator/UI it
  * sits below. Adding a format means adding its view and one case here — the exhaustive match makes
  * a missing mapping a COMPILE error (-Xfatal-warnings), so it cannot drift from the enum.
  */
object DiagramAttributesViews:

  def forFormat(format: DiagramFormat, state: ViewerState): HtmlElement =
    format match
      case DiagramFormat.DOT     => DiagramAttributesView(state)
      case DiagramFormat.Mermaid => MermaidDiagramAttributesView(state)
