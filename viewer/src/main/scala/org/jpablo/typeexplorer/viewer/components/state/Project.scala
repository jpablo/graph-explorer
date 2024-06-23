package org.jpablo.typeexplorer.viewer.components.state

import org.jpablo.typeexplorer.viewer.components.state.ViewerState.ActiveSymbols
import org.jpablo.typeexplorer.viewer.utils.Utils

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
    activeSymbols:  ActiveSymbols = Map.empty,
    diagramOptions: DiagramOptions = DiagramOptions()
)
