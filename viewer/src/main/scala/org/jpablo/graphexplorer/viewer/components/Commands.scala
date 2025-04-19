package org.jpablo.graphexplorer.viewer.components

import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.Command.{and, selectionNonEmpty, single}
import org.jpablo.graphexplorer.viewer.models.{ArrowDirection, ElementIds}
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom.{KeyValue, window}

import scala.collection.immutable.VectorMap

case class Shortcut(
    key:   String,
    shift: Boolean = false,
    meta:  Boolean = false,
    alt:   Boolean = false,
    ctrl:  Boolean = false
):
  def toList: List[String] =
    List((key, true), (KeyValue.Shift, shift), (KeyValue.Meta, meta), (KeyValue.Alt, alt), (KeyValue.Control, ctrl))
      .collect { case (str, true) => str }

case class Command(
    shortLabel:  String,
    action:      () => Unit,
    isVisible:   ElementIds => Boolean = _.nonEmpty,
    shortcut:    Option[Shortcut] = None,
    description: Option[String] = None
):
  def labelWithShortcut =
    description.getOrElse(shortLabel) + shortcut.fold("")(s => s" (${s.toList.mkString(" + ")})")

object Command:
  val always = (_: Any) => true

  def selectionNonEmpty(selection: ElementIds) = selection.nonEmpty

  def not(pred: ElementIds => Boolean)(selection: ElementIds): Boolean = !pred(selection)

  def and(p: ElementIds => Boolean, q: ElementIds => Boolean)(selection: ElementIds): Boolean =
    p(selection) && q(selection)

  def or(p: ElementIds => Boolean, q: ElementIds => Boolean)(selection: ElementIds): Boolean =
    p(selection) || q(selection)

  def single(selection: ElementIds): Boolean =
    selection.size == 1

class RouterCommands(router: Router):
  import Command.always

  private def createProjectAndNavigate() =
    val id = ProjectStorage.createProjectDirectoryEntry("Untitled")
    router.navigateTo(Route.ProjectDetail(id.value))

  val createProject =
    Command("Create new Project", createProjectAndNavigate, always, description = Some("Create a new project and navigate to it"))

  val navigateHome =
    Command("Navigate home", () => router.navigateTo(Route.Home), always, description = Some("Navigate to the home page"))

class Commands(state: ViewerState, val routerCmds: RouterCommands):
  import Command.{always, not}

  // -----------------------------------
  // miscellaneous actions
  // -----------------------------------
  private def changeProjectNameAction(): Unit =
    val newName = window.prompt("Enter project Name", state.project.name.now())
    if newName != null then
      state.project.name.set(newName)

  private def moveToGroupActionVisible(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.groups.size == 1 && classified.nodes.nonEmpty

  private def singleGroupSelected(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.groups.size == 1 && classified.nodes.isEmpty && classified.arrows.isEmpty

  private def singleNodeSelected(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.nodes.size == 1 && classified.groups.isEmpty && classified.arrows.isEmpty

  private def onlyArrowSelected(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.nodes.isEmpty && classified.groups.isEmpty && classified.arrows.nonEmpty

  private def singleElementSelected(selection: ElementIds): Boolean =
    selection.size == 1

  object all:
    val newNode =
      Command("New node", () => state.addNodeWithSmartConnection(), always, Some(Shortcut("n")), Some("Add a new node"))

    val newBackwardsNode = Command(
      "New backwards node",
      () => state.addNodeWithSmartConnection(direction = ArrowDirection.backward),
      singleNodeSelected,
      Some(Shortcut("p")),
      Some("Add a new node without connections")
    )

    val editLabel = Command(
      "Edit label",
      state.selection.editSelectedLabel,
      single,
      Some(Shortcut(KeyValue.Enter)),
      description = Some("Edit the label of the selected element")
    )

    val selectAll = Command(
      "Select all",
      state.selection.selectAll,
      always,
      Some(Shortcut("a")),
      description = Some("Select all visible elements (nodes, arrows, and groups)")
    )

    val selectAllNodes = Command(
      "Select all nodes",
      state.selection.selectAllVisibleNodes,
      always,
      description = Some("Select all visible nodes")
    )

    val selectAllArrows = Command(
      "Select all arrows",
      state.selection.selectAllVisibleArrows,
      always,
      description = Some("Select all visible arrows")
    )

    val selectAllGroups = Command(
      "Select all groups",
      state.selection.selectAllVisibleGroups,
      always,
      description = Some("Select all visible groups")
    )

    val hideSelection =
      Command("Hide selection", state.selection.hide, shortcut = Some(Shortcut("h")), description = Some("Hide selected nodes"))

    val keep = Command(
      "Keep selection",
      state.hideNonSelectedNodes,
      and(not(singleGroupSelected), selectionNonEmpty),
      Some(Shortcut("k")),
      description = Some("Hide all nodes except selected")
    )

    val delete = Command(
      "Delete selection",
      state.selection.deleteSelection,
      shortcut = Some(Shortcut(KeyValue.Backspace)),
      description = Some("Delete selected nodes")
    )

    val duplicate = Command(
      "Duplicate selection",
      state.selection.duplicateSelection,
      selectionNonEmpty,
      shortcut = Some(Shortcut("d")),
      description = Some("Duplicate selected nodes")
    )

    val group = Command(
      "Group",
      state.selection.group,
      and(not(onlyArrowSelected), selectionNonEmpty),
      shortcut = Some(Shortcut("g")),
      description = Some("Add selected nodes into a new group")
    )

    val moveToGroup = Command(
      "Move to group",
      () => state.selection.addToGroup(),
      moveToGroupActionVisible,
      description = Some("Move selected nodes to existing group")
    )

    val ungroup = Command(
      "Ungroup",
      state.selection.ungroup,
      shortcut = Some(Shortcut("u")),
      description = Some("Remove selected nodes from their current group")
    )

    val clearSelection = Command("Clear selection", state.selection.clear, shortcut = Some(Shortcut(KeyValue.Escape)))

    val selectGroupMembers = Command(
      "Select group members",
      state.selection.selectGroupMembers,
      singleGroupSelected,
      shortcut = Some(Shortcut("m")),
      description = Some("Select all nodes that are members of the selected group")
    )

    val zoomIntoGroup = Command(
      "Zoom into group",
      state.showOnlyGroup,
      singleGroupSelected,
      description = Some("Show only this group and its members")
    )

    val copyAsSVG = Command(
      "Copy selection as SVG",
      state.copySelectionAsSVG,
      shortcut = Some(Shortcut("c")),
      description = Some("Copy the selected nodes as SVG to the clipboard")
    )

    val showAllSuccessors = Command(
      "Show all successors",
      state.showAllSuccessors,
      description = Some("Show all successors of the selected nodes")
    )

    val showDirectSuccessors = Command(
      "Show direct successors",
      state.showDirectSuccessors,
      description = Some("Show direct successors of the selected nodes")
    )

    val selectAllSuccessors = Command(
      "Select all successors",
      state.selection.selectSuccessors,
      description = Some("Select all successors of the selected nodes")
    )

    val selectDirectSuccessors = Command(
      "Select direct successors",
      state.selection.selectDirectSuccessors,
      description = Some("Select direct successors of the selected nodes")
    )

    val showAllPredecessors = Command(
      "Show all predecessors",
      state.showAllPredecessors,
      description = Some("Show all predecessors of the selected nodes")
    )

    val showDirectPredecessors = Command(
      "Show direct predecessors",
      state.showDirectPredecessors,
      description = Some("Show direct predecessors of the selected nodes")
    )

    val selectAllPredecessors = Command(
      "Select all predecessors",
      state.selection.selectPredecessors,
      description = Some("Select all predecessors of the selected nodes")
    )

    val selectDirectPredecessors = Command(
      "Select direct predecessors",
      state.selection.selectDirectPredecessors,
      description = Some("Select direct predecessors of the selected nodes")
    )

    val rootsOnly    = Command("Show roots only", state.keepRootsOnly, always, description = Some("A root is a node without predecessors"))
    val showAll      = Command("Show all", state.showAll, always, description = Some("Show all elements"))
    val hideAllNodes = Command("Hide all", state.hideAllNodes, always, description = Some("Hide all nodes"))

    val changeProjectName = Command(
      "Change project name",
      changeProjectNameAction,
      always,
      description = Some("Change the name of the current project")
    )

    val exportAsSVG =
      Command(
        "Copy full diagram as SVG",
        state.copyAsFullDiagramSVG,
        always,
        description = Some("Copy the full diagram as SVG to the clipboard")
      )

    val exportAsDOT  = Command("as DOT", state.copyAsDOT, always, description = Some("Copy the full diagram as DOT to the clipboard"))
    val exportAsJSON = Command("as JSON", state.copyAsJSON, always, description = Some("Copy the full diagram as JSON to the clipboard"))
    val zoomOut      = Command("Zoom out", state.zoomOut, always, description = Some("Zoom out the diagram"))
    val fit          = Command("Fit", () => state.fitDiagram.emit(()), always, description = Some("Fit the diagram to the screen"))
    val zoomIn       = Command("Zoom in", state.zoomIn, always, description = Some("Zoom in the diagram"))
    val undo         = Command("Undo", () => state.undoEvent.emit(()), always, description = Some("Undo the last action"))
    val redo         = Command("Redo", () => state.redoEvent.emit(()), always, description = Some("Redo the last action"))

    val helpKeyboardShortcuts = Command(
      "Help - Keyboard Shortcuts",
      () => state.helpDialogOpen.set(true),
      always,
      description = Some("Open the keyboard shortcuts help dialog")
    )

    val printVisibleGraphToConsole = Command(
      "Print visible graph to the console",
      state.printVisibleGraphToConsole,
      always,
      description = Some("Print the visible graph to the browser console for debugging")
    )

    val printVisibleDOTtoConsole = Command(
      "Print visible DOT to the console",
      state.printVisibleDOTtoConsole,
      always,
      description = Some("Print the visible DOT to the browser console for debugging")
    )

    val printVisibleJSONtoConsole = Command(
      "Print JSON DOT AST to the console",
      state.printVisibleJSONtoConsole,
      always,
      description = Some("Print the full diagram as JSON DOT AST to console for debugging")
    )

    val printSelectionToConsole = Command(
      "Print the current selection to the console",
      state.printSelectionToConsole,
      always,
      description = Some("Print the current selection to console for debugging")
    )

    val resetAttributes = Command(
      "Reset Attributes",
      state.selection.resetAttributes, // Action to be implemented in ViewerState/SelectionHandler
      selectionNonEmpty,                       // Visible when selection is not empty
      shortcut = None,                         // No shortcut for now
      description = Some("Remove all attributes except 'label' from selected elements")
    )

    val resetLayout = Command(
      "Reset Layout",
      state.selection.resetLayout, // Action to be implemented in ViewerState/SelectionHandler
      selectionNonEmpty,                   // Visible when selection is not empty
      shortcut = None,                     // No shortcut for now
      description = Some("Reset the layout of the selected elements")
    )

    val reverseArrows = Command(
      "Reverse Arrows",
      state.selection.reverseArrows, // Action needs implementation in SelectionHandler
      onlyArrowSelected,                      // Visible only when arrows are selected
      shortcut = Some(Shortcut("r")),         // Shortcut 'r'
      description = Some("Reverse the direction of the selected arrows")
    )

  object headers:
    val common       = "Common"
    val selection    = "Selection"
    val successors   = "Successors"
    val predecessors = "Predecessors"
    val view         = "View"
    val document     = "Document"
    val exportAs     = "Export"
    val zoom         = "Zoom"
    val undoRedo     = "Undo/Redo"
    val application  = "Application"
    val developer    = "Developer"

  import headers.*

  val byHeader: VectorMap[String, List[Command]] = VectorMap(
    common -> List(
      all.newNode,
      all.newBackwardsNode,
      all.changeProjectName,
      all.moveToGroup,
      routerCmds.createProject,
      routerCmds.navigateHome
    ),
    selection -> List(
      all.hideSelection,
      all.keep,
      all.delete,
      all.duplicate,
      all.group,
      all.ungroup,
      all.resetAttributes,
      all.clearSelection,
      all.selectGroupMembers,
      all.zoomIntoGroup,
      all.copyAsSVG,
      all.editLabel,
      all.reverseArrows,
      all.selectAll,
      all.selectAllNodes,
      all.selectAllArrows,
      all.selectAllGroups
//      all.resetLayout
    ),
    successors -> List(
      all.showAllSuccessors,
      all.showDirectSuccessors,
      all.selectAllSuccessors,
      all.selectDirectSuccessors
    ),
    predecessors -> List(
      all.showAllPredecessors,
      all.showDirectPredecessors,
      all.selectAllPredecessors,
      all.selectDirectPredecessors
    ),
    view -> List(
      all.rootsOnly,
      all.showAll,
      all.hideAllNodes
    ),
    document -> List(
      all.changeProjectName,
      routerCmds.createProject
    ),
    exportAs -> List(
      all.copyAsSVG,
      all.exportAsSVG,
      all.exportAsDOT,
      all.exportAsJSON
    ),
    zoom -> List(
      all.zoomOut,
      all.fit,
      all.zoomIn
    ),
    undoRedo -> List(
      all.undo,
      all.redo
    ),
    application -> List(
      routerCmds.navigateHome,
      all.helpKeyboardShortcuts
    ),
    developer -> List(
      all.printVisibleGraphToConsole,
      all.printVisibleDOTtoConsole,
      all.printVisibleJSONtoConsole,
      all.printSelectionToConsole
    )
  )

  object sections:
    val exportAs = byHeader(headers.exportAs)

  val byShortcut: Map[Shortcut, Command] =
    byHeader.values.flatten
      .collect { case c @ Command(shortcut = Some(sh)) => sh -> c }
      .toMap

  def handleKeyDown(ev: dom.KeyboardEvent): Unit =
    val sh = Shortcut(ev.key, ev.shiftKey, ev.metaKey, ev.altKey, ev.ctrlKey)
    for cmd <- byShortcut.get(sh) do
      if ev.key == KeyValue.Enter then
        ev.preventDefault()
      cmd.action()
