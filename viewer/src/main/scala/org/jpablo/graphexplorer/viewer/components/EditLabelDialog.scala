package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.toRawText
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.{ElementId, ElementIds}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.SimpleDialog
import org.scalajs.dom.KeyValue

def EditLabelDialog(state: ViewerState) =
  val dialogIsOpen =
    state.editingElementV.zoomLazy(_.isDefined)((elem, open) => if open then elem else None)

  def elementLabelVar(elementId: ElementId): Var[String] =
    val attrUpdates = state.elementAttributesUpdates(ElementIds(Set(elementId)))
    toRawText(RowBuilder.simpleInputVar(Label.attrId, attrUpdates), "")

  def closeDialog() =
    state.editingElementV.set(None)

  val modalText: Var[String] = Var("")

  val textAreaElement = textArea(
    cls         := "textarea textarea-bordered w-full", // Added border for visibility
    placeholder := "Enter label text...",
    rows        := 3,                                   // Give it a bit more initial space
    controlled(value <-- modalText, onInput.mapToValue --> modalText),
//    onMountFocus,
    focus <-- dialogIsOpen.signal.changes.tapEach(f => pprint.log(f, "dialogIsOpen")),
    // Handle Enter -> Save and Close
    // Shift+Enter -> Add a new line
    onKeyDown.filter(ev => ev.key == KeyValue.Enter && !ev.shiftKey) --> { ev =>
      ev.preventDefault()
      val curValue = modalText.now()
      state.editingElementV.now().foreach(elementLabelVar(_).set(curValue))
      closeDialog()
    },
    // Handle Escape -> Close without saving
    onKeyDown.filter(_.key == KeyValue.Escape) --> closeDialog()
  )

  SimpleDialog(
    open = dialogIsOpen,
    // --- Dialog Content ---
    div(
      h3(cls := "font-bold text-md mb-2", "Edit Label"),
      textAreaElement,
      div(cls := "flex justify-between items-center mt-2", p(cls := "text-xs text-gray-500", "Press Shift+Enter to add a new line"))
    ),
    // --- State Synchronization ---
    // Update modalText when the dialog opens
    state.editingElementV.signal.map(_.fold("")(elementLabelVar(_).now())) --> modalText,
    // Makes sure the focus is restored to CanvasContainer after the dialog is closed
    dialogIsOpen.signal.changes.filter(!_) --> state.canvasContainerFocus.set(true)
  ).amend(idAttr := "edit-label-dialog")
