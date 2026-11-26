package org.jpablo.graphexplorer.viewer.components.labelEditor

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{ElementId, GroupId, NodeId}
import org.jpablo.graphexplorer.viewer.state.ViewerState

import scala.annotation.tailrec

/** A [[LabelEditor]] for the diagram of `state`, as the label dialogs use it.
  *
  * The preview uses the graph of the canvas, the Graphviz engine of the
  * canvas, and the dark-color setting of the canvas. A Mermaid diagram has no
  * preview and no HTML error line: Mermaid draws its labels with other shapes,
  * other fonts and other rules for HTML, so a Graphviz drawing would be wrong.
  * For the same reason, a Mermaid label has no tab "Formatted": its tab shows
  * the reason, and the editor shows the source.
  *
  * The tab "Formatted" uses `graph` and `element` for the font of the
  * element (see `LabelPreview.elementFont`) and for the faces of the diagram.
  *
  * @param graph   the graph of the preview; `state.fullGraph` for a label of
  *                the diagram
  * @param element the element of the label in `graph`
  * @param startCell the cell of a table label where the cursor starts
  */
def DiagramLabelEditor(
    state:    ViewerState,
    text:     Var[String],
    graph:    Signal[ViewerGraph],
    element:  Signal[ElementId],
    onSave:   () => Unit,
    onCancel: () => Unit,
    hint:     String,
    previewAsText: Boolean = false,
    startCell: () => Option[List[Int]] = () => None
): HtmlElement =
  LabelEditor(
    text        = text,
    graph       = graph,
    element     = element,
    render      = dot => state.graphviz.textToSvgOnly(DotText(dot)),
    onSave      = onSave,
    onCancel    = onCancel,
    hint        = hint,
    graphviz    = state.currentFormat.map(_ == DiagramFormat.DOT),
    darkColors  = state.diagramDarkColors.signal,
    previewAsText = previewAsText,
    startCell   = startCell
  )

/** The drawing of `label` as the canvas draws the label of `element`, with
  * the Graphviz engine and the dark-color setting of the canvas. The Title
  * row of the Diagram panel shows the title with it.
  */
def DiagramLabelDrawing(state: ViewerState, label: Signal[String], element: ElementId): HtmlElement =
  LabelEditorParts.preview(
    label,
    state.fullGraph,
    Signal.fromValue(element),
    dot => state.graphviz.textToSvgOnly(DotText(dot)),
    state.diagramDarkColors.signal
  )

/** The ids of new elements in the label dialogs. */
object NewLabelIds:

  /** A node id that is not in `graph`. */
  def node(graph: ViewerGraph): NodeId =
    NodeId(freeName("gx-new-node", name => graph.nodes.contains(NodeId(name))))

  /** A group id that is not in `graph`. */
  def group(graph: ViewerGraph): GroupId =
    GroupId(freeName("gx-new-group", name => graph.groups.contains(GroupId(name))))

  private def freeName(base: String, taken: String => Boolean): String =
    @tailrec
    def loop(n: Int): String =
      val name = if n == 0 then base else s"$base-$n"
      if taken(name) then loop(n + 1) else name
    loop(0)
