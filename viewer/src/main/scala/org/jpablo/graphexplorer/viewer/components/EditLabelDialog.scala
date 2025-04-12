package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.views.buildFieldSets
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{InputType, SimpleDialog}
import com.raquo.laminar.api.features.unitArrows
import org.scalajs.dom.KeyValue

def EditLabelDialog(state: ViewerState) =
  val isOpen =
    state.editingElementV.zoomLazy(_.isDefined)((elem, open) => if open then elem else None)

  val control =
    state.editingElementV.signal.map: eId =>
      val builder = RowBuilder(updates = state.elementAttributesUpdates(ElementIds(eId.toSet)), layout = state.graphLayout)
      val row     = builder.row(Label, InputType.multiText(setFocus = true), onReset = Some(""))
      buildFieldSets(Seq(row))

  SimpleDialog(
    open = isOpen,
    children <-- control,
    // Makes sure the focus is restored to CanvasContainer when dialog is closed
    isOpen.signal.changes.filter(!_) --> state.canvasContainerFocus.set(true),
    onKeyDown.filter(ev => ev.key == KeyValue.Enter && ev.metaKey) --> isOpen.set(false)
  ).amend(idAttr := "edit-label-dialog")
