package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveElement
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.IconButtonTitled

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
