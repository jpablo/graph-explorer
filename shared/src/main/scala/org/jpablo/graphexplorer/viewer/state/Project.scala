package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.models.{DiagramId, ElementIds, GroupId}

case class Project(
    id:   DiagramId,
    name: String = "",
    page: Page = Page()
)

case class Page(
    hiddenElements: HiddenElements = ElementIds(),
    // Groups rendered as a single box. A VIEW setting like hiddenElements —
    // it never touches the source text — so it is persisted per page and
    // restored with the diagram.
    collapsedGroups: Set[GroupId] = Set.empty
)
