package org.jpablo.typeexplorer.viewer.components.state

import org.jpablo.typeexplorer.viewer.models.GraphSymbol

import scala.scalajs.js

case class Project(
    id:              ProjectId,
    name:            String = "",
    advancedMode:    Boolean = false,
    packagesOptions: PackagesOptions = PackagesOptions(),
    projectSettings: ProjectSettings = ProjectSettings(),
    page:            Page = Page(),
    activePage:      Int = 0
)

type ActiveSymbolsSeq = List[(GraphSymbol, Option[SymbolOptions])]

case class Page(
    id: String = js.Dynamic.global.crypto.randomUUID().toString,
    // This can't be a Map[A, Option[B]], as zio-json will remove entries with None values
    activeSymbols:  ActiveSymbolsSeq = List.empty,
    diagramOptions: DiagramOptions = DiagramOptions()
)
