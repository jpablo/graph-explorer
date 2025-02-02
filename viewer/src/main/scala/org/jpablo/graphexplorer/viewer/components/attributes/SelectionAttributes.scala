package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.models.NodeId.isArrowId
import org.jpablo.graphexplorer.viewer.models.NodeId.isClusterId

def SelectionAttributes(state: ViewerState) =
  div(
    child <--
      state.diagramSelection.signal.map: selectedNodes =>
        val (arrowIds, notArrows) = selectedNodes.partition(isArrowId)
        val (clusterIds, nodeIds) = notArrows.partition(isClusterId)
        
        (arrowIds.nonEmpty, nodeIds.nonEmpty, clusterIds.nonEmpty) match
          case (true, false, false) => EdgesAttributesView(state, state.nodesAttributes(arrowIds), selection = true).amend(cls("selection-attributes"))
          case (false, true, false) => NodesAttributesView("SelectionAttributes", state, state.nodesAttributes(nodeIds), selection = true ).amend(cls("selection-attributes"))
          case (false, false, true) => GraphAttributesView(state, state.nodesAttributes(clusterIds), selection = true).amend(cls("selection-attributes"))
          case _ => emptyNode
  )
