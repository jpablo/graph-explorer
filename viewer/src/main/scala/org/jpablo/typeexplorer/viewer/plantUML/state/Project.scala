package org.jpablo.typeexplorer.viewer.plantUML.state

import com.softwaremill.quicklens.*
import org.jpablo.typeexplorer.viewer.models.GraphSymbol

import scala.scalajs.js

case class Project(
    id:              ProjectId,
    name:            String = "",
    advancedMode:    Boolean = false,
    packagesOptions: PackagesOptions = PackagesOptions(),
    projectSettings: ProjectSettings = ProjectSettings(),
    pages:           Vector[Page] = Vector(Page()),
    activePage:      Int = 0
):
  private def validActivePage: Int =
    if activePage < 0 then 0
    else if activePage >= pages.size then pages.size - 1
    else activePage

  def activePageId: String =
    pages(validActivePage).id

  def setActivePageId(id: String): Project =
    val i = pages.zipWithIndex.find((p, _) => p.id == id).map(_._2).getOrElse(0)
    this.modify(_.activePage).setTo(i)

type ActiveSymbolsSeq = List[(GraphSymbol, Option[SymbolOptions])]

case class Page(
    id: String = js.Dynamic.global.crypto.randomUUID().toString,
    // This can't be a Map[A, Option[B]], as zio-json will remove entries with None values
    activeSymbols:  ActiveSymbolsSeq = List.empty,
    diagramOptions: DiagramOptions = DiagramOptions()
)
