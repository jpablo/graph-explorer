package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.keys.EventProp
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom.window
import org.jpablo.graphexplorer.viewer.models.NodeId

import scala.collection.immutable.VectorMap

case class Command(
    title:     String,
    action:    EventProp[dom.MouseEvent] => Modifier[ReactiveHtmlElement.Base],
    isVisible: Set[NodeId] => Boolean = _.nonEmpty,
    shortcut:  Option[String] = None
):
  def titleWithShortcut =
    shortcut.fold(title)(sh => s"$title ($sh)")

class Commands(state: ViewerState, router: Router):
  import state.eventHandlers.*

  private val always = (_: Any) => true

  private val copyToClipboard = window.navigator.clipboard.writeText

  private def changeProjectNameAction(): Unit =
    val newName = window.prompt("Enter project Name", state.project.name.now())
    if newName != null then
      state.project.name.set(newName)

  val menuSections: VectorMap[String, List[Command]] = VectorMap(
    "Common" -> List(
      Command("Add node", _ --> state.addNode(), always, shortcut = Some("n"))
    ),
    "Selection" -> List(
      Command("Hide", hideSelectedNodes, shortcut           = Some("h")),
      Command("Hide others", hideNonSelectedNodes, shortcut = Some("Shift+h")),
      Command("Delete", deleteSelectedNodes, shortcut       = Some("Del")),
      Command("Group", groupSelectedNodes, shortcut         = Some("g")),
      Command("Clear selection", clearSelection, shortcut   = Some("Esc")),
      //
      Command("Copy as SVG", _.copySelectionAsSVG(copyToClipboard), shortcut = Some("c"))
    ),
    "Successors" -> List(
      Command("Show all successors", showAllSuccessors),
      Command("Show direct successors", showDirectSuccessors),
      Command("Select all successors", selectSuccessors),
      Command("Select direct successors", selectDirectSuccessors)
    ),
    "Predecessors" -> List(
      Command("Show all predecessors", showAllPredecessors),
      Command("Show direct predecessors", showDirectPredecessors),
      Command("Select all predecessors", selectPredecessors),
      Command("Select direct predecessors", selectDirectPredecessors)
    ),
    "View" -> List(
      Command("Roots only", keepRootsOnly, always),
      Command("Show all", _ --> state.showAllNodes(), always),
      Command("Hide all", hideAllNodes, always)
    ),
    "Export" -> List(
      Command("as SVG", _.copyAsFullDiagramSVG(copyToClipboard), always),
      Command("as DOT", _.copyAsDOT(copyToClipboard), always),
      Command("as JSON DOT AST", _.copyAsJSON(copyToClipboard), always)
    ),
    "Zoom" -> List(
      Command("Zoom out", _ --> state.zoomValue.update(_ * 0.9), always),
      Command("Fit", _ --> state.fitDiagram.emit(()), always),
      Command("Zoom in", _ --> state.zoomValue.update(_ * 1.1), always)
    ),
    "Undo/Redo" -> List(
      Command("Undo", _ --> state.undoEvent.emit(()), always),
      Command("Redo", _ --> state.redoEvent.emit(()), always)
    ),
    "Application" -> List(
      Command("Navigate home", _ --> router.navigateTo(Route.Home), always),
      Command("Change project name", _ --> changeProjectNameAction(), always),
      Command("Help - Keyboard Shortcuts", _ --> state.shortcutsModalOpen.set(true), always),
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
