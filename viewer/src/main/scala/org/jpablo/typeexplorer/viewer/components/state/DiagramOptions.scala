package org.jpablo.typeexplorer.viewer.components.state

import org.jpablo.typeexplorer.viewer.models
import org.jpablo.typeexplorer.viewer.models.{ViewerKind, ViewerNodeId}

type Path = String

// packages tree configuration
case class PackagesOptions(
    onlyActive: Boolean = false,
    nsKind:     Set[ViewerKind] = Set.empty
)

// project configuration
case class ProjectSettings(
    basePaths:     List[Path] = List.empty,
    hiddenFields:  List[String] = DiagramOptions.hiddenFields,
    hiddenSymbols: List[ViewerNodeId] = DiagramOptions.hiddenSymbols
)

// diagram configuration (tab specific)
case class DiagramOptions(
    showFields:     Boolean = false,
    showSignatures: Boolean = false
)

case class SymbolOptions(
    showFields:     Boolean = false,
    showSignatures: Boolean = false
)

object DiagramOptions:

  val hiddenFields = List(
    "canEqual",
    "copy",
    "equals",
    "hashCode",
    "productArity",
    "productElement",
    "productIterator",
    "productPrefix",
    "toString",
    "_1",
    "_2",
    "_3",
    "_4"
  )

  val hiddenSymbols = List()

end DiagramOptions
