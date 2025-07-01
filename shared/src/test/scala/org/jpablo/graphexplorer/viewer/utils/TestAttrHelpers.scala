package org.jpablo.graphexplorer.viewer.utils

import org.jpablo.graphexplorer.viewer.graph.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttributeTarget
import org.jpablo.graphexplorer.viewer.models.*

/** Test helpers for attribute updates that rely on core normalization.
  *
  * These helpers route changes through the normal update paths so that
  * fill normalization is applied:
  *   - FillColor != none  => FillStyle=true
  *   - FillColor == none  => FillStyle=false
  */
object TestAttrHelpers:

  /** Set element FillColor via update path (triggers normalization). */
  def setFillColor(graph: ViewerGraph, nodeId: NodeId, color: String): ViewerGraph =
    graph.updateAttributes(ElementIds.from(nodeId), AttributeUpdates.of(FillColor -> color))

  /** Clear element fill (sets FillColor=none and FillStyle=false via normalization). */
  def clearFill(graph: ViewerGraph, nodeId: NodeId): ViewerGraph =
    graph.updateAttributes(ElementIds.from(nodeId), AttributeUpdates.of(FillColor -> FillColor.none))

  /** Set default node FillColor via default update path (triggers normalization). */
  def setDefaultNodeFillColor(graph: ViewerGraph, color: String): ViewerGraph =
    AttributesOps.defaultAttributesUpdates(AttributeTarget.node).update(graph, AttributeUpdates.of(FillColor -> color))

  /** Clear default node fill (sets FillStyle=false on defaults via normalization). */
  def clearDefaultNodeFill(graph: ViewerGraph): ViewerGraph =
    AttributesOps.defaultAttributesUpdates(AttributeTarget.node).update(graph, AttributeUpdates.of(FillColor -> FillColor.none))
