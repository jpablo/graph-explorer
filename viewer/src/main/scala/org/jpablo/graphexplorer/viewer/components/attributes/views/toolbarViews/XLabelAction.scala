package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveElement
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputAttribute, toRawText}
import org.jpablo.graphexplorer.viewer.components.labelEditor.DiagramLabelDrawing
import org.jpablo.graphexplorer.viewer.formats.dot.HtmlLabels
import org.jpablo.graphexplorer.viewer.models.ElementId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{IconButtonTitled, InputWithValue}

/** The button of the XLabel row: it opens the external label of the selected element in the Edit External Label
  * dialog, with the label editor. The text box of the row has one line, and an external label can be markup with
  * fonts. The button shows only for one selected element, because the dialog edits one label.
  */
def xlabelAction(state: ViewerState): () => ReactiveElement.Base = () =>
  IconButtonTitled(
    "bi-pencil",
    "Edit the external label",
    cls("hidden") <-- state.selection.signal.map(_.size != 1)
  ):
    val current = state.selection.now()
    if current.size == 1 then state.editingXLabelV.set(Some(current.head))

/** The XLabel row of the "More attributes" card, for nodes and for arrows.
  *
  * The row shows a text box. When one element is selected and its external label is markup, the row shows the label
  * as the canvas draws it, in a button that opens the Edit External Label dialog. A text box shows markup as text.
  * The Title row of the Diagram panel works in the same way.
  *
  * While the text box has the focus, the row keeps the same text box, also when the person types markup. Thus the
  * focus and the caret stay. When the focus leaves the text box, the row draws a markup label.
  */
def xlabelRow(state: ViewerState, row: InputAttribute): InputAttribute =
  row.copy(labelAction = Some(xlabelAction(state)), customCell = Some(() => xlabelCell(state, row)))

private def xlabelCell(state: ViewerState, row: InputAttribute): HtmlElement =
  val text = toRawText(row.inputVar, "").signal
  // True while the text box of the row has the focus.
  val boxFocused = Var(false)
  // The element whose external label the row draws. None gives the text box.
  val drawn: Signal[Option[ElementId]] =
    state.selection.signal
      .combineWith(text, boxFocused.signal)
      .map: (selection, label, focused) =>
        Option.when(!focused && selection.size == 1 && HtmlLabels.isHtml(label))(selection.head)
      .distinct
  span(
    cls := "gx-xlabel-cell",
    child <-- drawn.map:
      case Some(element) =>
        button(
          typ        := "button",
          cls        := "gx-xlabel-shown",
          title      := "Edit the external label",
          aria.label := "The external label; click to edit it",
          onClick --> (_ => state.editingXLabelV.set(Some(element))),
          DiagramLabelDrawing(state, text, element, asText = true)
        )
      case None =>
        InputWithValue(row).amend(
          aria.label := row.label,
          onFocus.mapTo(true) --> boxFocused,
          onBlur.mapTo(false) --> boxFocused
        )
  )
