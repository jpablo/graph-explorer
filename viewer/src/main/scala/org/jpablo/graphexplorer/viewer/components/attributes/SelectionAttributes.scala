package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.models.Arrow.isArrowId

def SelectionAttributes(state: ViewerState) =
  div(
    child <--
      state.diagramSelection.signal.map: selectedNodes =>
        val (arrowIds, nodeIds) = selectedNodes.partition(isArrowId)
        if arrowIds.nonEmpty && nodeIds.isEmpty then
          EdgesAttributesView(state.nodesAttributes(arrowIds)).amend(cls("selection-attributes"))
        else if nodeIds.nonEmpty && arrowIds.isEmpty then
          NodesAttributesView(state.nodesAttributes(nodeIds)).amend(cls("selection-attributes"))
        else
          emptyNode // Can't edit mixed selection
  )
