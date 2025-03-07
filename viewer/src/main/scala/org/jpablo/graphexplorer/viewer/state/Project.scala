package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.models.ElementIds
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
    diagramOptions: DiagramOptions = DiagramOptions()
)
