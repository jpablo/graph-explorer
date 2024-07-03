package org.jpablo.typeexplorer.viewer.state

import org.jpablo.typeexplorer.viewer.models
import org.jpablo.typeexplorer.viewer.models.{ViewerKind, NodeId}

type Path = String

// packages tree configuration
case class PackagesOptions(
    onlyActive: Boolean = false,
    kinds:      Set[ViewerKind] = Set.empty
)

// project configuration
case class ProjectSettings(
    basePaths:     List[Path] = List.empty,
    hiddenFields:  List[String] = DiagramOptions.hiddenFields,
    hiddenNodeIds: List[NodeId] = DiagramOptions.hiddenSymbols
)

// diagram configuration (tab specific)
case class DiagramOptions(
    showFields:     Boolean = false,
    showSignatures: Boolean = false
)

case class NodeOptions(
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