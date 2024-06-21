package org.jpablo.typeexplorer.viewer.components.state

import zio.json.*
import org.jpablo.typeexplorer.viewer.models
import org.jpablo.typeexplorer.viewer.models.GraphSymbol

type Path = String

// packages tree configuration
case class PackagesOptions(
    onlyActive: Boolean = false,
    onlyTests:  Boolean = false,
    nsKind:     Set[models.NamespaceKind] = models.NamespaceKind.values.toSet
) derives JsonCodec

// project configuration
case class ProjectSettings(
    basePaths:     List[Path] = List.empty,
    hiddenFields:  List[String] = DiagramOptions.hiddenFields,
    hiddenSymbols: List[GraphSymbol] = DiagramOptions.hiddenSymbols
) derives JsonCodec

// diagram configuration (tab specific)
case class DiagramOptions(
    showFields:     Boolean = false,
    showSignatures: Boolean = false
) derives JsonCodec

case class SymbolOptions(
    showFields:     Boolean = false,
    showSignatures: Boolean = false
) derives JsonCodec

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
