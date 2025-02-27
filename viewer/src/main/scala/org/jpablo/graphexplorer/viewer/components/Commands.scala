package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom.window

import scala.collection.immutable.VectorMap

case class Command(
    title:     String,
    action:    () => Unit,
    isVisible: Set[NodeId] => Boolean = _.nonEmpty,
    shortcut:  Option[String] = None
):
  def titleWithShortcut =
    shortcut.fold(title)(sh => s"$title ($sh)")

class Commands(state: ViewerState, router: Router):

  private val always = (_: Any) => true

  private def changeProjectNameAction(): Unit =
    val newName = window.prompt("Enter project Name", state.project.name.now())
    if newName != null then
      state.project.name.set(newName)

  val menuSections: VectorMap[String, List[Command]] = VectorMap(
    "Common" -> List(
      Command("Add node", state.addNode, always, shortcut = Some("n"))
    ),
    "Selection" -> List(
      Command("Hide", state.hideSelection, shortcut               = Some("h")),
      Command("Hide others", state.hideNonSelectedNodes, shortcut = Some("Shift+h")),
      Command("Delete", state.deleteSelection, shortcut           = Some("Backspace")),
      Command("Duplicate", state.duplicateSelection, shortcut     = Some("d")),
      Command("Group", state.groupSelectedNodes, shortcut         = Some("g")),
      Command("Clear selection", state.clearSelection, shortcut   = Some("Esc")),
      //
      Command("Copy as SVG", state.copySelectionAsSVG, shortcut = Some("c"))
    ),
    "Successors" -> List(
      Command("Show all successors", state.showAllSuccessors),
      Command("Show direct successors", state.showDirectSuccessors),
      Command("Select all successors", state.selectSuccessors),
      Command("Select direct successors", state.selectDirectSuccessors)
    ),
    "Predecessors" -> List(
      Command("Show all predecessors", state.showAllPredecessors),
      Command("Show direct predecessors", state.showDirectPredecessors),
      Command("Select all predecessors", state.selectPredecessors),
      Command("Select direct predecessors", state.selectDirectPredecessors)
    ),
    "View" -> List(
      Command("Roots only", state.keepRootsOnly, always),
      Command("Show all", state.showAllNodes, always),
      Command("Hide all", state.hideAllNodes, always)
    ),
    "Export" -> List(
      Command("as SVG", state.copyAsFullDiagramSVG, always),
      Command("as DOT", state.copyAsDOT, always),
      Command("as JSON DOT AST", state.copyAsJSON, always)
    ),
    "Zoom" -> List(
      Command("Zoom out", () => state.zoomValue.update(_ * 0.9), always),
      Command("Fit", () => state.fitDiagram.emit(()), always),
      Command("Zoom in", () => state.zoomValue.update(_ * 1.1), always)
    ),
    "Undo/Redo" -> List(
      Command("Undo", () => state.undoEvent.emit(()), always),
      Command("Redo", () => state.redoEvent.emit(()), always)
    ),
    "Application" -> List(
      Command("Navigate home", () => router.navigateTo(Route.Home), always),
      Command("Change project name", changeProjectNameAction, always),
      Command("Help - Keyboard Shortcuts", () => state.shortcutsModalOpen.set(true), always)
    )
  )

  object sections:
    val common = menuSections("Common")
    val selection = menuSections("Selection")
    val successors = menuSections("Successors")
    val predecessors = menuSections("Predecessors")
    val view = menuSections("View")
    val exportAs = menuSections("Export")
    val zoom = menuSections("Zoom")
    val undoRedo = menuSections("Undo/Redo")
    val application = menuSections("Application")

  // some special cases for the menu
  val addNode = sections.common.find(_.title == "Add node").get
  val List(zoomOut, fit, zoomIn) = sections.zoom
  val List(undo, redo) = sections.undoRedo
  val List(navigateHome, changeProjectName, keyboardShortcuts) = sections.application

  val commandsByShortcut: Map[String, Command] =
    menuSections.values.flatten
      .collect { case c @ Command(_, _, _, Some(shortcut)) => shortcut -> c }
      .toMap

  def handleKeyDown(ev: dom.KeyboardEvent): Unit =
    val prefix = if ev.shiftKey then "Shift+" else ""
    commandsByShortcut.get(prefix + ev.key) match
      case Some(cmd) =>
        ev.preventDefault()
        cmd.action()
      case None =>
        ()

