package org.jpablo.graphexplorer.viewer.components

import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.models.ElementIds
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom.window

import scala.collection.immutable.VectorMap

case class Command(
    title:       String,
    action:      () => Unit,
    isVisible:   ElementIds => Boolean = _.nonEmpty,
    shortcut:    List[String] = Nil,
    description: Option[String] = None
):
  def titleWithShortcut =
    description.getOrElse(title) + (if shortcut.nonEmpty then s" (${shortcut.mkString(" + ")})" else "")

object Command:
  val always = (_: Any) => true
  def not(pred: ElementIds => Boolean)(selection: ElementIds): Boolean = !pred(selection)

class RouterCommands(router: Router):
  import Command.always

  private def createProjectAndNavigate() =
    val id = ProjectStorage.createProjectDirectoryEntry("Untitled")
    router.navigateTo(Route.ProjectDetail(id.value))

  val createProject =
    Command(
      "Create new Project",
      createProjectAndNavigate,
      always,
      description = Some("Create a new project and navigate to it")
    )

  val navigateHome =
    Command(
      "Navigate home",
      () => router.navigateTo(Route.Home),
      always,
      description = Some("Navigate to the home page")
    )

class Commands(state: ViewerState, routerCmds: RouterCommands):
  import Command.{not, always}

  private def changeProjectNameAction(): Unit =
    val newName = window.prompt("Enter project Name", state.project.name.now())
    if newName != null then
      state.project.name.set(newName)

  private def moveToGroupActionVisible(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.clusters.size == 1 && classified.nodes.nonEmpty

  private def isSingleGroupSelected(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.clusters.size == 1 && classified.nodes.isEmpty && classified.arrows.isEmpty

  object headers:
    val common = "Common"
    val selection = "Selection"
    val successors = "Successors"
    val predecessors = "Predecessors"
    val view = "View"
    val document = "Document"
    val exportAs = "Export"
    val zoom = "Zoom"
    val undoRedo = "Undo/Redo"
    val application = "Application"
    val developer = "Developer"

  val bySection: VectorMap[String, List[Command]] = VectorMap(
    headers.common -> List(
      Command(
        "Add node",
        () => { state.addNodeWithSmartConnection(); () },
        always,
        shortcut    = List("n"),
        description = Some("Add a new node")
      ),
      Command(
        "Select all",
        state.selection.selectAll,
        always,
        shortcut    = List("a"),
        description = Some("Select all visible elements (nodes, arrows, and groups)")
      ),
      Command(
        "Select all nodes",
        state.selection.selectAllVisibleNodes,
        always,
        description = Some("Select all visible nodes")
      ),
      Command(
        "Select all arrows",
        state.selection.selectAllVisibleArrows,
        always,
        description = Some("Select all visible arrows")
      ),
      Command(
        "Select all groups",
        state.selection.selectAllVisibleGroups,
        always,
        description = Some("Select all visible groups")
      )
    ),
    headers.selection -> List(
      Command("Hide", state.selection.hide, shortcut = List("h"), description = Some("Hide selected nodes")),
      Command(
        "Keep",
        state.hideNonSelectedNodes,
        not(isSingleGroupSelected),
        shortcut    = List("k"),
        description = Some("Hide all nodes except selected")
      ),
      Command(
        "Delete",
        state.selection.deleteSelection,
        shortcut    = List("Backspace"),
        description = Some("Delete selected nodes")
      ),
      Command(
        "Duplicate",
        state.selection.duplicateSelection,
        not(isSingleGroupSelected),
        shortcut    = List("d"),
        description = Some("Duplicate selected nodes")
      ),
      Command(
        "Group",
        state.selection.group,
        shortcut    = List("g"),
        description = Some("Add selected nodes into a new group")
      ),
      Command(
        "Move to group",
        state.selection.addToGroup,
        moveToGroupActionVisible,
        description = Some("Add selected nodes to the selected group")
      ),
      Command(
        "Ungroup",
        state.selection.ungroup,
        shortcut    = List("u"),
        description = Some("Remove selected nodes from their current group")
      ),
      Command("Clear selection", state.selection.clear, shortcut = List("Esc")),
      //
      Command(
        "Select group members",
        state.selection.selectGroupMembers,
        shortcut    = List("m"),
        description = Some("Select all nodes that are members of the selected group")
      ),
      Command(
        "Zoom into group",
        state.showOnlyGroup,
        isSingleGroupSelected,
        description = Some("Show only this group and its members")
      ),
      Command(
        "Copy as SVG",
        state.copySelectionAsSVG,
        shortcut    = List("c"),
        description = Some("Copy the selected nodes as SVG to the clipboard")
      )
    ),
    headers.successors -> List(
      Command(
        "Show all successors",
        state.showAllSuccessors,
        description = Some("Show all successors of the selected nodes")
      ),
      Command(
        "Show direct successors",
        state.showDirectSuccessors,
        description = Some("Show direct successors of the selected nodes")
      ),
      Command(
        "Select all successors",
        state.selection.selectSuccessors,
        description = Some("Select all successors of the selected nodes")
      ),
      Command(
        "Select direct successors",
        state.selection.selectDirectSuccessors,
        description = Some("Select direct successors of the selected nodes")
      )
    ),
    headers.predecessors -> List(
      Command(
        "Show all predecessors",
        state.showAllPredecessors,
        description = Some("Show all predecessors of the selected nodes")
      ),
      Command(
        "Show direct predecessors",
        state.showDirectPredecessors,
        description = Some("Show direct predecessors of the selected nodes")
      ),
      Command(
        "Select all predecessors",
        state.selection.selectPredecessors,
        description = Some("Select all predecessors of the selected nodes")
      ),
      Command(
        "Select direct predecessors",
        state.selection.selectDirectPredecessors,
        description = Some("Select direct predecessors of the selected nodes")
      )
    ),
    headers.view -> List(
      Command("Roots only", state.keepRootsOnly, always, description = Some("A root is a node without predecessors")),
      Command("Show all", state.showAllNodes, always, description    = Some("Show all hidden nodes")),
      Command("Hide all", state.hideAllNodes, always, description    = Some("Hide all nodes"))
    ),
    headers.document -> List(
      Command("Change project name", changeProjectNameAction, always, description = Some("Change the project name")),
      routerCmds.createProject
    ),
    headers.exportAs -> List(
      Command(
        "as SVG",
        state.copyAsFullDiagramSVG,
        always,
        description = Some("Copy the full diagram as SVG to the clipboard")
      ),
      Command("as DOT", state.copyAsDOT, always, description = Some("Copy the full diagram as DOT to the clipboard"))
    ),
    headers.zoom -> List(
      Command("Zoom out", () => state.zoomValue.update(_ * 0.9), always, description = Some("Zoom out the diagram")),
      Command("Fit", () => state.fitDiagram.emit(()), always, description = Some("Fit the diagram to the screen")),
      Command("Zoom in", () => state.zoomValue.update(_ * 1.1), always, description = Some("Zoom in the diagram"))
    ),
    headers.undoRedo -> List(
      Command("Undo", () => state.undoEvent.emit(()), always, description = Some("Undo the last action")),
      Command("Redo", () => state.redoEvent.emit(()), always, description = Some("Redo the last action"))
    ),
    headers.application -> List(
      routerCmds.navigateHome,
      Command(
        "Help - Keyboard Shortcuts",
        () => state.shortcutsModalOpen.set(true),
        always,
        description = Some("Open the keyboard shortcuts help dialog")
      )
    ),
    headers.developer -> List(
      Command(
        "Print visible graph to the console",
        state.printVisibleGraphToConsole,
        always,
        description = Some("Print the visible graph to the browser console for debugging")
      ),
      Command(
        "Print visible DOT to the console",
        state.printVisibleDOTtoConsole,
        always,
        description = Some("Print the visible DOT to the browser console for debugging")
      ),
      Command(
        "Print JSON DOT AST to the console",
        state.printVisibleJSONtoConsole,
        always,
        description = Some("Print the full diagram as JSON DOT AST to console for debugging")
      )
    )
  )

  object sections:
    val exportAs = bySection(headers.exportAs)

  // some special cases for the menu
  val addNode = bySection(headers.common).find(_.title == "Add node").get
  val List(zoomOut, fit, zoomIn) = bySection(headers.zoom)
  val List(rootsOnly, showAll, hideAll) = bySection(headers.view)
  val List(undo, redo) = bySection(headers.undoRedo)
  val List(changeProjectName, createProject) = bySection(headers.document)
  val List(navigateHome, keyboardShortcuts) = bySection(headers.application)

  val byShortcut: Map[List[String], Command] =
    bySection.values.flatten
      .collect { case c: Command if c.shortcut.nonEmpty => c.shortcut -> c }
      .toMap

  def handleKeyDown(ev: dom.KeyboardEvent): Unit =
    val sh = if ev.shiftKey then List("Shift", ev.key) else List(ev.key)
    for cmd <- byShortcut.get(sh) do
      ev.preventDefault()
      cmd.action()
