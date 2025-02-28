package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom.window

import scala.collection.immutable.VectorMap

case class Command(
    title:       String,
    action:      () => Unit,
    isVisible:   Set[NodeId] => Boolean = _.nonEmpty,
    shortcut:    List[String] = Nil,
    description: Option[String] = None
):
  def titleWithShortcut =
    val sh = shortcut.mkString(" (", " + ", ")")
    title + sh

class Commands(state: ViewerState, router: Router):

  private val always = (_: Any) => true

  private def changeProjectNameAction(): Unit =
    val newName = window.prompt("Enter project Name", state.project.name.now())
    if newName != null then
      state.project.name.set(newName)

  private def moveToGroupActionVisible(selection: Set[NodeId]): Boolean =
    val classified = state.classifyNodes(selection.toSeq)
    classified.clusters.size == 1 && classified.nodes.nonEmpty

  val menuSections: VectorMap[String, List[Command]] = VectorMap(
    "Common" -> List(
      Command("Add node", state.addNode, always, shortcut = List("n"), description = Some("Add a new node"))
    ),
    "Selection" -> List(
      Command("Hide", state.hideSelection, shortcut               = List("h"), description = Some("Hide selected nodes")),
      Command("Hide others", state.hideNonSelectedNodes, shortcut = List("Shift", "h"), description = Some("Hide all nodes except selected")),
      Command("Delete", state.deleteSelection, shortcut           = List("Backspace"), description = Some("Delete selected nodes")),
      Command("Duplicate", state.duplicateSelection, shortcut     = List("d"), description = Some("Duplicate selected nodes")),
      Command("Group", state.groupSelectedNodes, shortcut         = List("g"), description = Some("Add selected nodes into a new group")),
      Command("Move to group", state.addSelectionToGroup, moveToGroupActionVisible, description = Some("Add selected nodes to the selected group")),
      Command("Clear selection", state.clearSelection, shortcut = List("Esc")),
      //
      Command("Copy as SVG", state.copySelectionAsSVG, shortcut = List("c"), description = Some("Copy the selected nodes as SVG to the clipboard"))
    ),
    "Successors" -> List(
      Command("Show all successors", state.showAllSuccessors, description = Some("Show all successors of the selected nodes")),
      Command("Show direct successors", state.showDirectSuccessors, description = Some("Show direct successors of the selected nodes")),
      Command("Select all successors", state.selectSuccessors, description = Some("Select all successors of the selected nodes")),
      Command("Select direct successors", state.selectDirectSuccessors, description = Some("Select direct successors of the selected nodes"))
    ),
    "Predecessors" -> List(
      Command("Show all predecessors", state.showAllPredecessors, description = Some("Show all predecessors of the selected nodes")),
      Command("Show direct predecessors", state.showDirectPredecessors, description = Some("Show direct predecessors of the selected nodes")),
      Command("Select all predecessors", state.selectPredecessors, description = Some("Select all predecessors of the selected nodes")),
      Command("Select direct predecessors", state.selectDirectPredecessors, description = Some("Select direct predecessors of the selected nodes"))
    ),
    "View" -> List(
      Command("Roots only", state.keepRootsOnly, always, description = Some("A root is a node without predecessors")),
      Command("Show all", state.showAllNodes, always, description = Some("Show all nodes")),
      Command("Hide all", state.hideAllNodes, always, description = Some("Hide all nodes"))
    ),
    "Export" -> List(
      Command("as SVG", state.copyAsFullDiagramSVG, always, description = Some("Copy the full diagram as SVG to the clipboard")),
      Command("as DOT", state.copyAsDOT, always, description = Some("Copy the full diagram as DOT to the clipboard")),
      Command("as JSON DOT AST", state.copyAsJSON, always, description = Some("Copy the full diagram as JSON DOT AST to the clipboard"))
    ),
    "Zoom" -> List(
      Command("Zoom out", () => state.zoomValue.update(_ * 0.9), always, description = Some("Zoom out the diagram")),
      Command("Fit", () => state.fitDiagram.emit(()), always, description = Some("Fit the diagram to the screen")),
      Command("Zoom in", () => state.zoomValue.update(_ * 1.1), always, description = Some("Zoom in the diagram"))
    ),
    "Undo/Redo" -> List(
      Command("Undo", () => state.undoEvent.emit(()), always, description = Some("Undo the last action")),
      Command("Redo", () => state.redoEvent.emit(()), always, description = Some("Redo the last action"))
    ),
    "Application" -> List(
      Command("Navigate home", () => router.navigateTo(Route.Home), always, description = Some("Navigate to the home page")),
      Command("Change project name", changeProjectNameAction, always, description = Some("Change the project name")),
      Command("Help - Keyboard Shortcuts", () => state.shortcutsModalOpen.set(true), always, description = Some("Open the keyboard shortcuts help dialog"))
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

  val commandsByShortcut: Map[List[String], Command] =
    menuSections.values.flatten
      .collect { case c @ Command(_, _, _, sh @ _ :: _, _) => sh -> c }
      .toMap

  def handleKeyDown(ev: dom.KeyboardEvent): Unit =
    val sh = if ev.shiftKey then List("Shift", ev.key) else List(ev.key)
    for cmd <- commandsByShortcut.get(sh) do
      ev.preventDefault()
      cmd.action()
