package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.utils.Utils

case class Project(
    id:              ProjectId,
    name:            String = "",
    advancedMode:    Boolean = false,
    packagesOptions: PackagesOptions = PackagesOptions(),
    projectSettings: ProjectSettings = ProjectSettings(),
    page:            Page = Page()
)

case class Page(
    id: String = Utils.randomUUID(),
    // The symbols currently shown in the diagram?
    visibleNodes:   VisibleNodes = Map.empty,
    diagramOptions: DiagramOptions = DiagramOptions()
)
