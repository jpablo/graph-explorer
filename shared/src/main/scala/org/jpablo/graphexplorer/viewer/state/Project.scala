package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.models.{ElementIds, GroupId}
import org.jpablo.graphexplorer.viewer.utils.Utils

case class Project(
    id:              ProjectId,
    name:            String = "",
    advancedMode:    Boolean = false,
    projectSettings: ProjectSettings = ProjectSettings(),
    page:            Page = Page()
)

case class Page(
    id:             String = Utils.randomUUID(),
    hiddenElements: HiddenElements = ElementIds(),
    // Groups rendered as a single box. A VIEW setting like hiddenElements —
    // it never touches the source text — so it is persisted per page and
    // restored with the diagram.
    collapsedGroups: Set[GroupId] = Set.empty,
    diagramOptions: DiagramOptions = DiagramOptions()
)
