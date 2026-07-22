package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.state.ViewerState

/** Resolves the diagram-level attributes view for a given format.
  *
  * This keeps `RightPanel` (and any other consumer) free of per-format `match`es. The mapping lives
  * here, in the views layer, rather than on `DiagramBackend`: the views need the full `ViewerState`,
  * and pushing them onto the backend would make the backend layer depend on the orchestrator/UI it
  * sits below. Adding a format means adding its view and one entry here.
  */
object DiagramAttributesViews:
  private val byFormat: Map[DiagramFormat, ViewerState => HtmlElement] =
    Map(
      DiagramFormat.DOT     -> (DiagramAttributesView(_)),
      DiagramFormat.Mermaid -> (MermaidDiagramAttributesView(_))
    )

  /** The diagram-attributes view for `format`. Totality over the enum is guaranteed by
    * DiagramAttributesViewsSpec, so a missing mapping fails loudly instead of silently
    * rendering the DOT editor for the wrong language.
    */
  def forFormat(format: DiagramFormat, state: ViewerState): HtmlElement =
    byFormat(format)(state)

  /** Formats with an explicit view here. A test asserts this covers every `DiagramFormat`, so the
    * mapping can't silently drift from the format enum / backend registry.
    */
  def knownFormats: Set[DiagramFormat] = byFormat.keySet
