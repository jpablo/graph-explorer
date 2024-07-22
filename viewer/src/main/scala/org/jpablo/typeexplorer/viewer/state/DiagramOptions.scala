package org.jpablo.typeexplorer.viewer.state

import org.jpablo.typeexplorer.viewer.models
import org.jpablo.typeexplorer.viewer.models.{NodeId, ViewerKind}
import upickle.default.*


type Path = String

// packages tree configuration
case class PackagesOptions(
    onlyActive: Boolean = false,
    kinds:      Set[ViewerKind] = Set.empty
)

// project configuration
case class ProjectSettings(
    basePaths:     List[Path] = List.empty,
    hiddenFields:  List[String] = List.empty,
    hiddenNodeIds: List[NodeId] = List.empty
)

// diagram configuration (tab specific)
case class DiagramOptions(
    showFields:     Boolean = false,
    showSignatures: Boolean = false
)

case class NodeOptions(
    showFields:     Boolean = false,
    showSignatures: Boolean = false
) derives ReadWriter



