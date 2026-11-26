package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.toRawText
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, XLabel}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraphElements
import org.jpablo.graphexplorer.viewer.models.{ElementId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.components.labelEditor.DiagramLabelEditor
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Dialog, PrimaryAction, QuietAction}

def EditLabelDialog(state: ViewerState) =
  def elementLabelVar(elementId: ElementId): Var[String] =
    val attrUpdates = state.elementAttributesUpdates(ElementIds(Set(elementId)))
    toRawText(RowBuilder.simpleInputVar(Label.attrId, attrUpdates), "")

  // The dialog shows only while an element is in `editingElementV`. The
  // empty node id is for the closed dialog, which shows no preview.
  LabelDialog(
    state     = state,
    domId     = "edit-label-dialog",
    heading   = "Edit Label",
    open      = state.editingElementV.signal.map(_.isDefined),
    close     = () => state.editingElementV.set(None),
    labelVar  = () => state.editingElementV.now().map(elementLabelVar),
    element   = state.editingElementV.signal.map(_.getOrElse(NodeId(""))),
    hint      = "Enter label text..."
  )

/** The dialog of the title of the diagram: the `label` of the root graph.
  *
  * The Title row of the Diagram panel opens it (`editingTitleV`). The panel is
  * too narrow for the label editor, so the editor is in a dialog, as the
  * label of a node is. The preview draws the title with the font of the graph.
  */
def EditTitleDialog(state: ViewerState) =
  LabelDialog(
    state    = state,
    domId    = "edit-title-dialog",
    heading  = "Edit Title",
    open     = state.editingTitleV.signal,
    close    = () => state.editingTitleV.set(false),
    labelVar = () => Some(toRawText(RowBuilder.simpleInputVar(Label.attrId, state.diagramAttributesUpdates), "")),
    // A group that is not in the graph gets the font of the graph (`LabelPreview.elementFont`).
    element  = Signal.fromValue(ViewerGraphElements.defaultRootId),
    hint     = "Enter the title..."
  )

/** The dialog of the external label (`xlabel`) of a node or an arrow.
  *
  * The XLabel row of the "More attributes" card opens it (`editingXLabelV`).
  * The preview draws the label as the canvas does: as text with the font of
  * the element, outside of the element and with no shape.
  */
def EditXLabelDialog(state: ViewerState) =
  def xlabelVar(elementId: ElementId): Var[String] =
    val attrUpdates = state.elementAttributesUpdates(ElementIds(Set(elementId)))
    toRawText(RowBuilder.simpleInputVar(XLabel.attrId, attrUpdates), "")

  LabelDialog(
    state         = state,
    domId         = "edit-xlabel-dialog",
    heading       = "Edit External Label",
    open          = state.editingXLabelV.signal.map(_.isDefined),
    close         = () => state.editingXLabelV.set(None),
    labelVar      = () => state.editingXLabelV.now().map(xlabelVar),
    element       = state.editingXLabelV.signal.map(_.getOrElse(NodeId(""))),
    hint          = "Enter the external label...",
    previewAsText = true
  )

/** A modal dialog with the label editor for one label.
  *
  * @param open     true while the dialog shows
  * @param close    closes the dialog
  * @param labelVar the label text of the dialog, while it is open
  * @param element  the element of the label, for the preview and the font
  */
private def LabelDialog(
    state:    ViewerState,
    domId:    String,
    heading:  String,
    open:     Signal[Boolean],
    close:    () => Unit,
    labelVar: () => Option[Var[String]],
    element:  Signal[ElementId],
    hint:     String,
    previewAsText: Boolean = false
) =
  val modalText: Var[String] = Var("")

  def saveAndClose(): Unit =
    labelVar().foreach(_.set(modalText.now()))
    close()

  def labelEditor = DiagramLabelEditor(
    state    = state,
    text     = modalText,
    graph    = state.fullGraph,
    element  = element,
    onSave   = () => saveAndClose(),
    onCancel = () => close(),
    hint     = hint,
    previewAsText = previewAsText
  )

  div(
    idAttr := domId,
    child(
      Dialog(onDismiss = () => close())(
        // --- contents ---
        div(
          h3(cls := "font-bold text-md mb-2", heading),
          labelEditor
        ),
        // --- State Synchronization ---
        // Update modalText when the dialog opens. The signal, not its changes:
        // the dialog mounts after `open` is true, and the mount gives the value.
        open.map(isOpen => if isOpen then labelVar().fold("")(_.now()) else "") --> modalText,
        // Makes sure the focus is restored to CanvasContainer after the dialog is closed
        open --> { isOpen =>
          if !isOpen then
            state.canvasContainerFocus.emit(true)
        }
      )(
        // --- actions ---
        div(
          cls := "flex justify-end gap-2 mt-2", // Added gap and margin-top
          // Cancel recedes: it was a filled, outlined button standing beside the
          // primary, so the dialog offered two equally weighted ways out.
          QuietAction("Cancel", onClick --> close()),
          PrimaryAction("Ok", onClick --> saveAndClose())
        )
      )
    ) <-- open
  )
