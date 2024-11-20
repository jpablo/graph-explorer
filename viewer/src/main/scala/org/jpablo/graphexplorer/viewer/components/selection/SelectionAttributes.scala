package org.jpablo.graphexplorer.viewer.components.selection

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.buildRows
import org.jpablo.graphexplorer.viewer.components.attributes.AttributesView
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number}

def SelectionAttributes(state: ViewerState) =
  div(
    child <--
      state.diagramSelection.signal.map: selectedNodes =>
        if selectedNodes.isEmpty then
          emptyNode
        else
          CombinedAttributesView(state.nodesAttributes(selectedNodes.map(_.value)))
  )

def CombinedAttributesView(attrs: Var[Map[String, String]]) =
  AttributesView(
    id    = "selection-attributes",
    title = "Selection Attributes",
    attrs = attrs,
    rows = buildRows(
      Label,
      Shape,
      Style,
      URL,
      Color     -> color,
      FillColor -> color,
      LabelLoc,
      FontSize  -> number,
      FontColor -> color,
      FontName,
      Ordering,
      Orientation -> number,
      PenWidth    -> number,
      Peripheries -> number,
      Sides       -> number,
      Regular     -> checkbox,
      // --- nodes only ---
      ArrowHead,
      ArrowTail,
      Dir,
      Decorate -> checkbox
    )
  )
// url
