package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.models.*

case class FlattenedGraphElement(
    rootId:      GroupId,
    arrows:      List[Arrow],
    groups:      List[ViewerGroup],
    nodes:       List[ViewerNode],
    memberships: List[(ElementId, GroupId)] = Nil
)
