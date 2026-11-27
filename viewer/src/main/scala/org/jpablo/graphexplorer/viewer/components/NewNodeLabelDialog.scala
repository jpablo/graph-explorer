package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.components.labelEditor.{DiagramLabelEditor, NewLabelIds}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{Attributes, NodeId}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{Dialog, PrimaryAction, QuietAction}

/** Dialog to collect a label before creating a new node. It mirrors the EditLabelDialog UX but triggers node creation on save. */
def NewNodeLabelDialog(state: ViewerState) =
  val dialogIsOpen =
    state.pendingNewNodeV.zoomLazy(_.isDefined)((pending, open) => if open then pending else None)

  val modalText: Var[String] = Var("")

  def closeDialog(): Unit =
    state.pendingNewNodeV.set(None)

  def saveAndCreate(): Unit =
    state.pendingNewNodeV.now().foreach: pending =>
      val text   = modalText.now()
      // The rule of the Edit Label dialog: HTML-like markup stays HTML.
      val attrs  = pending.attributes + (Label.attrId -> AttributeRow.labelValue(text))
      val dir    = pending.direction
      // Create node with provided label, then select/focus continues as usual. In a free layout, the node goes to
      // the place of the gesture that opened the dialog.
      state.addNodeWithSmartConnection(attrs, dir, pending.at)
    closeDialog()

  /** The graph of the preview: the diagram with the new node, which has the
    * attributes of the new node (a shape of the toolbar, for example). The id
    * of the new node is not in the diagram.
    */
  val previewGraph: Signal[(ViewerGraph, NodeId)] =
    state.fullGraph.combineWithFn(state.pendingNewNodeV.signal): (graph, pending) =>
      val id = NewLabelIds.node(graph)
      (graph.addNodeWithId(id, attributes = pending.fold(Attributes.empty)(_.attributes)), id)

  def labelEditor = DiagramLabelEditor(
    state    = state,
    text     = modalText,
    graph    = previewGraph.map(_._1),
    element  = previewGraph.map(_._2),
    onSave   = () => saveAndCreate(),
    onCancel = () => closeDialog(),
    hint     = "Enter label text..."
  )

  div(
    idAttr := "new-node-label-dialog",
    child(
      Dialog(onDismiss = () => closeDialog())(
        // --- contents ---
        div(
          h3(cls := "font-bold text-md mb-2", "Edit Label"),
          labelEditor
        ),
        // --- State Synchronization ---
        // Reset modal text when the dialog opens
        state.pendingNewNodeV.signal.map(_ => "") --> modalText,
        // Make sure the focus is restored to CanvasContainer after the dialog is closed
        dialogIsOpen.signal --> { open =>
          if !open then
            state.canvasContainerFocus.emit(true)
        }
      )(
        // --- actions ---
        div(
          cls := "flex justify-end gap-2 mt-2",
          // Cancel recedes: it was a filled, outlined button standing beside the
          // primary, so the dialog offered two equally weighted ways out.
          QuietAction("Cancel", onClick --> closeDialog()),
          PrimaryAction("Ok", onClick --> saveAndCreate())
        )
      )
    ) <-- dialogIsOpen
  )

