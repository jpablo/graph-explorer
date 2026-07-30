package org.jpablo.graphexplorer.viewer.components

import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.Command.{and, selectionNonEmpty, single}
import org.jpablo.graphexplorer.viewer.models.{ArrowDirection, ElementIds}
import org.jpablo.graphexplorer.viewer.state.{NavDirection, PersistedDiagramState, RightPanelSection, ViewerState}
import org.scalajs.dom.KeyValue
import org.scalajs.dom

import scala.scalajs.js
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
end Command

/** Wrapper to allow actions of zero or one argument
  */
enum CmdAction[-A]:
  private case NoArg(f: () => Unit)
  private case OneArg(f: A => Unit)

  def execute(a: Option[A]): Unit =
    (this, a) match
      case (NoArg(f), _)        => f()
      case (OneArg(f), Some(v)) => f(v)
      case _                    => throw new IllegalArgumentException("argument required")

object CmdAction:
  given Conversion[() => Unit, CmdAction[Nothing]] = NoArg(_)
  given [A] => Conversion[A => Unit, CmdAction[A]] = OneArg(_)

case class Command[-A](
    shortLabel:         String,
    private val action: CmdAction[A],
    isVisible:          ElementIds => Boolean = selectionNonEmpty,
    shortcut:           Option[Shortcut] = None,
    description:        Option[String] = None
):
  def labelWithShortcut =
    description.getOrElse(shortLabel) + shortcut.fold("")(s => s" (${s.toList.mkString(" + ")})")

  def execute(arg: Option[A] = None, logEvent: Boolean = true): Unit =
    // Log to GA — unless the caller marks this as a repeat (a held-down arrow
    // key auto-repeats ~30×/s; streaming one GA event per repeat is noise).
    if logEvent then
      val commandIdentifier = description.getOrElse(shortLabel)
      val p = js.Dynamic.literal(
        "command_label"  -> commandIdentifier,
        "event_category" -> "Command",
        "event_label"    -> commandIdentifier
      )
      val gtag = js.Dynamic.global.selectDynamic("gtag")
      if js.typeOf(gtag) == "function" then
        gtag("event", "command_executed", p)
    action.execute(arg)

class RouterCommands(router: Router):
  import Command.always

  private def createProjectAndNavigate(source: Option[String] = None) =
    val id = ProjectStorage.createProjectDirectoryEntry(PersistedDiagramState.defaultProjectName)
    router.navigateTo(Route.ProjectDetail(id.value, source))

  val createProject =
    Command(
      "Create new Project",
      (source: Option[String]) => createProjectAndNavigate(source),
      always,
      description = Some("Create a new project and navigate to it")
    )

  val navigateHome =
    Command("Navigate home", () => router.navigateTo(Route.Home), always, description = Some("Navigate to the home page"))

class Commands(state: ViewerState, val routerCmds: RouterCommands):
  import Command.{always, not}

  // -----------------------------------
  // miscellaneous actions
  // -----------------------------------
  private def changeProjectNameAction(): Unit =
    // Opens the app's own rename dialog (RenameProjectDialog) — the native
    // window.prompt was the one browser-chrome interruption left in the flow.
    state.renameDialogOpen.set(true)

  private def moveToGroupActionVisible(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.groups.size == 1 && classified.nodes.nonEmpty

  private def singleGroupSelected(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.groups.size == 1 && classified.nodes.isEmpty && classified.arrows.isEmpty

  /** A group is selected — or the proxy box standing for a collapsed one, which
    * the DOM reports as a node (see ViewerState.resolveCollapsed). */
  private def groupOrCollapsedBox(selection: ElementIds): Boolean =
    val resolved = state.resolveCollapsed(selection).classify
    resolved.groups.nonEmpty && resolved.arrows.isEmpty

  private def singleNodeSelected(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.nodes.size == 1 && classified.groups.isEmpty && classified.arrows.isEmpty

  private def onlyArrowSelected(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.nodes.isEmpty && classified.groups.isEmpty && classified.arrows.nonEmpty

  private def singleElementSelected(selection: ElementIds): Boolean =
    selection.size == 1

  private def canCombineNodesVisible(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.nodes.size >= 2 && classified.arrows.isEmpty && classified.groups.isEmpty

  private def canSplitRecordVisible(selection: ElementIds): Boolean =
    val classified = selection.classify
    classified.nodes.size == 1 && classified.arrows.isEmpty && classified.groups.isEmpty &&
      state.fullGraphNow().isRecordNode(classified.nodes.head)

  private def cellSelected(selection: ElementIds): Boolean =
    state.selectedCellV.now().isDefined

  object all:
    val newNode =
      Command(
        "New node",
        () => state.createNodeMaybePrompt(),
        always,
        Some(Shortcut("n")),
        Some("Add a new node")
      )

    val newBackwardsNode = Command(
      "New backwards node",
      () => state.createNodeMaybePrompt(direction = ArrowDirection.backward),
      singleNodeSelected,
      // Shift of its sibling `n` (New node), like b/B and s/S. It moved off bare `p`
      // so the predecessor family could have the letter every user guesses for it.
      Some(Shortcut("n", shift = true)),
      Some("Add a new node without connections")
    )

    val editLabel = Command(
      "Edit label",
      // On a record node Enter first descends into cell selection, then edits
      // the selected cell; everything else opens the whole-label dialog.
      () => if !state.recordCells.enterOrEdit() then state.selection.editSelectedLabel(),
      single,
      Some(Shortcut(KeyValue.Enter)),
      description = Some("Edit the label of the selected element (records: select/edit the cell)")
    )

    val selectAll = Command(
      "Select all",
      () => state.selection.selectAll(),
      always,
      Some(Shortcut("a")),
      description = Some("Select all visible elements (nodes, arrows, and groups)")
    )

    val selectAllNodes = Command(
      "Select all nodes",
      () => state.selection.selectAllVisibleNodes(),
      always,
      description = Some("Select all visible nodes")
    )

    val selectAllArrows = Command(
      "Select all arrows",
      () => state.selection.selectAllVisibleArrows(),
      always,
      description = Some("Select all visible arrows")
    )

    val selectAllGroups = Command(
      "Select all groups",
      () => state.selection.selectAllVisibleGroups(),
      always,
      description = Some("Select all visible groups")
    )

    val hideSelection =
      Command("Hide selection", () => state.selection.hide(), shortcut = Some(Shortcut("h")), description = Some("Hide selected nodes"))

    val keep = Command(
      "Keep selection",
      () => state.hideNonSelectedNodes(),
      and(not(singleGroupSelected), selectionNonEmpty),
      Some(Shortcut("k")),
      description = Some("Hide all nodes except selected")
    )

    val delete = Command(
      "Delete selection",
      // With a record CELL selected, Backspace removes the cell — not the node.
      () => if !state.recordCells.removeSelectedCell() then state.selection.deleteSelection(),
      shortcut = Some(Shortcut(KeyValue.Backspace)),
      description = Some("Delete selected nodes (records: the selected cell)")
    )

    val duplicate = Command(
      "Duplicate selection",
      () => state.selection.duplicateSelection(),
      selectionNonEmpty,
      shortcut = Some(Shortcut("d")),
      description = Some("Duplicate selected nodes")
    )

    val group = Command(
      "Group",
      () => state.selection.group(),
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
      () => state.selection.ungroup(),
      shortcut = Some(Shortcut("u")),
      description = Some("Remove selected nodes from their current group")
    )

    val clearSelection = Command(
      "Clear selection",
      // Escape pops ONE level: a selected record cell first, then the selection.
      () => if !state.recordCells.escapeCell() then state.selection.clear(),
      shortcut = Some(Shortcut(KeyValue.Escape))
    )

    val combineIntoRecord = Command(
      "Combine into Record",
      () => state.selection.combineIntoRecord(),
      canCombineNodesVisible,
      shortcut = Some(Shortcut("b")),
      description = Some("Combine selected nodes into a record node")
    )

    val splitRecord = Command(
      "Split Record",
      () => state.selection.splitRecord(),
      canSplitRecordVisible,
      shortcut = Some(Shortcut("b", shift = true)),
      description = Some("Split record node into individual nodes")
    )

    val transposeRecord = Command(
      "Transpose Record",
      () => state.selection.transposeRecord(),
      canSplitRecordVisible,  // Same visibility condition as split
      shortcut = Some(Shortcut("t")),
      description = Some("Toggle record node between horizontal and vertical orientation")
    )

    val selectGroupMembers = Command(
      "Select group members",
      () => state.selection.selectGroupMembers(),
      singleGroupSelected,
      shortcut = Some(Shortcut("m")),
      description = Some("Select all nodes that are members of the selected group")
    )

    val zoomIntoGroup = Command(
      "Zoom into group",
      () => state.showOnlyGroup(),
      singleGroupSelected,
      shortcut = Some(Shortcut("z")),
      description = Some("Show only the selected group and its members")
    )

    val toggleCollapseGroup = Command(
      "Collapse/expand group",
      () => state.selection.toggleCollapse(),
      // Also true when the COLLAPSED BOX is selected — that box is a node as
      // far as the DOM is concerned, so the raw predicate would refuse to
      // expand what it just collapsed.
      groupOrCollapsedBox,
      shortcut = Some(Shortcut("e")),
      description = Some("Render the selected group as a single box, or unfold it again")
    )

    // The one-directional versions, for a mixed selection where "toggle" would
    // guess — and the graph-wide pair, which needs no selection at all.
    val collapseSelectedGroups = Command(
      "Collapse selected groups",
      () => state.selection.collapse(),
      groupOrCollapsedBox,
      description = Some("Render each selected group as a single box")
    )

    val expandSelectedGroups = Command(
      "Expand selected groups",
      () => state.selection.expand(),
      groupOrCollapsedBox,
      description = Some("Unfold the selected collapsed groups")
    )

    val collapseAllGroups = Command(
      "Collapse all groups",
      () => state.collapseAllGroups(),
      always,
      description = Some("Render every group in the diagram as a single box")
    )

    val expandAllGroups = Command(
      "Expand all groups",
      () => state.expandAllGroups(),
      always,
      description = Some("Unfold every collapsed group")
    )

    val toggleLayoutAnimation = Command(
      "Toggle layout animation",
      () => state.animateLayoutChanges.update(!_),
      always,
      description = Some("Animate elements to their new place when the layout changes")
    )

    val copyAsSVG = Command(
      "Copy selection as SVG",
      () => state.copySelectionAsSVG(),
      shortcut = Some(Shortcut("c")),
      description = Some("Copy the selected nodes as SVG to the clipboard")
    )

    val showAllSuccessors = Command(
      "Show all successors",
      () => state.showAllSuccessors(),
      description = Some("Show all successors of the selected nodes")
    )

    val showDirectSuccessors = Command(
      "Show direct successors",
      () => state.showDirectSuccessors(),
      shortcut = Some(Shortcut("+")),
      description = Some("Show direct successors of the selected nodes")
    )

    val hideSuccessorsRecursive = Command(
      "Hide successors (recursive)",
      () => state.hideSuccessors(recursive = true),
      shortcut = Some(Shortcut("-")),
      description = Some("Hide outgoing arrows; hide successors that lose all incoming arrows, recursively")
    )

    val hideSuccessorLayer = Command(
      "Hide successor layer",
      () => state.hideSuccessors(recursive = false),
      description = Some("Hide outgoing arrows; hide successors that lose all incoming arrows (one layer)")
    )

    val selectAllSuccessors = Command(
      "Select all successors",
      () => state.selection.selectSuccessors(),
      // pairs with bare `s` (direct successors), like b/B for combine/split
      shortcut = Some(Shortcut("s", shift = true)),
      description = Some("Select all successors of the selected nodes")
    )

    val toggleSuccessors = Command(
      "Expand/contract successors",
      () => state.toggleSuccessors(),
      description = Some(
        "Tree-style toggle: show the selected nodes' hidden direct successors, or hide the successor layer if none are hidden"
      ),
      // '.' points forward, ',' backward — a mirror pair like +/- and s/p.
      shortcut = Some(Shortcut("."))
    )

    val selectDirectSuccessors = Command(
      "Select direct successors",
      () => state.selection.selectDirectSuccessors(),
      // `d` was the natural pick but Duplicate owns it; `s` = successors, sitting
      // next to the rest of the family (`+` show direct, `-` hide recursive).
      // Bare `s` cannot collide with the desktop save bridge: that path requires
      // meta/ctrl and is intercepted before shortcut dispatch.
      shortcut = Some(Shortcut("s")),
      description = Some("Select direct successors of the selected nodes")
    )

    val togglePredecessors = Command(
      "Expand/contract predecessors",
      () => state.togglePredecessors(),
      description = Some(
        "Tree-style toggle: show the selected nodes' hidden direct predecessors, or hide the predecessor layer if none are hidden"
      ),
      shortcut = Some(Shortcut(","))
    )

    val hidePredecessorsRecursive = Command(
      "Hide predecessors (recursive)",
      () => state.hidePredecessors(recursive = true),
      description = Some("Hide incoming arrows; hide predecessors that lose all outgoing arrows, recursively")
    )

    val hidePredecessorLayer = Command(
      "Hide predecessor layer",
      () => state.hidePredecessors(recursive = false),
      description = Some("Hide incoming arrows; hide predecessors that lose all outgoing arrows (one layer)")
    )

    val showAllPredecessors = Command(
      "Show all predecessors",
      () => state.showAllPredecessors(),
      description = Some("Show all predecessors of the selected nodes")
    )

    val showDirectPredecessors = Command(
      "Show direct predecessors",
      () => state.showDirectPredecessors(),
      description = Some("Show direct predecessors of the selected nodes")
    )

    val selectAllPredecessors = Command(
      "Select all predecessors",
      () => state.selection.selectPredecessors(),
      // mirrors S (all successors): shift = transitive, bare = one hop
      shortcut = Some(Shortcut("p", shift = true)),
      description = Some("Select all predecessors of the selected nodes")
    )

    val selectDirectPredecessors = Command(
      "Select direct predecessors",
      () => state.selection.selectDirectPredecessors(),
      // `p` = predecessors, the mirror of bare `s` for successors. Bare `p` used to
      // create a backwards node; that command now sits on `N`, next to `n`.
      shortcut = Some(Shortcut("p")),
      description = Some("Select direct predecessors of the selected nodes")
    )

    val rootsOnly =
      Command("Show roots only", () => state.keepRootsOnly(), always, description = Some("A root is a node without predecessors"))
    val showAll      = Command("Show all", () => state.showAll(), always, description = Some("Show all elements"))
    val hideAllNodes = Command("Hide all", () => state.hideAllNodes(), always, description = Some("Hide all nodes"))

    val deleteHiddenElements = Command(
      "Delete hidden elements",
      () => state.deleteHiddenElements(),
      always,
      description = Some("Delete all currently hidden nodes, arrows, and groups")
    )

    val changeProjectName = Command(
      "Change project name",
      () => changeProjectNameAction(),
      always,
      description = Some("Change the name of the current project")
    )

    val exportAsSVG =
      Command(
        "Copy full diagram as SVG",
        () => state.copyAsFullDiagramSVG(),
        always,
        description = Some("Copy the full diagram as SVG to the clipboard")
      )

    val exportAsDOT =
      Command("as DOT", () => state.copyAsDOT(), always, description = Some("Copy the full diagram as DOT to the clipboard"))
    val exportAsJSON =
      Command("as JSON", () => state.copyAsJSON(), always, description = Some("Copy the full diagram as JSON to the clipboard"))

    val copyShareURL = Command(
      "Share URL",
      () => {
        val dot  = state.sourceText.now()
        val url  = org.jpablo.graphexplorer.viewer.utils.ShareUrl.buildForProject(state.projectId, dot)
        state.writeText(url)
        dom.console.info("Share URL copied to clipboard", url)
        state.infoBus.emit("Link copied to clipboard")
      },
      always,
      description = Some("Copy a URL to this diagram (local only)")
    )
    val zoomOut = Command("Zoom out", () => state.zoomOut(), always, description = Some("Zoom out the diagram"))
    val fit     = Command("Fit", () => state.fitDiagram.emit(()), always, description = Some("Fit the diagram to the screen"))
    val autoFit = Command("Auto fit", () => state.autoFitToggle(), always, description = Some("Zoom in the diagram"))
    val zoomIn  = Command("Zoom in", () => state.zoomIn(), always, description = Some("Zoom in the diagram"))
    val undo    = Command("Undo", () => state.undoEvent.emit(()), always, description = Some("Undo the last action"))
    val redo    = Command("Redo", () => state.redoEvent.emit(()), always, description = Some("Redo the last action"))

    val findElements = Command(
      "Find elements",
      // Setting the section (even when it is already active) re-fires the
      // palette's focus-the-filter reaction, so `/` is open-or-refocus.
      () => state.rightPanelActiveSection.set(RightPanelSection.elements),
      always,
      shortcut = Some(Shortcut("/")),
      description = Some("Open the Elements palette with the filter focused")
    )

    val helpKeyboardShortcuts = Command(
      "Help - Keyboard Shortcuts",
      () => state.helpDialogOpen.set(true),
      always,
      description = Some("Open the keyboard shortcuts help dialog")
    )

    val openAboutDialog = Command( // Add command to open AboutDialog
      "About",
      () => state.aboutDialogOpen.set(true),
      always,
      description = Some("Show application information")
    )

    val printVisibleGraphToConsole = Command(
      "Print visible graph to the console",
      () => state.printVisibleGraphToConsole(),
      always,
      description = Some("Print the visible graph to the browser console for debugging")
    )

    val printVisibleGraphJsonToConsole = Command(
      "Print visible graph as json to the console",
      () => state.printVisibleGraphJsonToConsole(),
      always,
      description = Some("Print the visible graph as json to the browser console for debugging")
    )

    val printVisibleDOTtoConsole = Command(
      "Print visible DOT to the console",
      () => state.printVisibleDOTtoConsole(),
      always,
      description = Some("Print the visible DOT to the browser console for debugging")
    )

    val printVisibleSimpleGraphJSONtoConsole = Command(
      "Print Visible Simple Graph Json to the console",
      () => state.printVisibleSimpleGraphJSONtoConsole(),
      always,
      description = Some("Print the full diagram as graph JSON to console for debugging")
    )

    val printSelectionToConsole = Command(
      "Print the current selection to the console",
      () => state.printSelectionToConsole(),
      always,
      description = Some("Print the current selection to console for debugging")
    )

    val resetSelectionAttributes = Command(
      "Reset Attributes",
      () => state.selection.resetAttributes(), // Action to be implemented in ViewerState/SelectionHandler
      selectionNonEmpty,                       // Visible when selection is not empty
      shortcut = None,                         // No shortcut for now
      description = Some("Remove all attributes except 'label' from selected elements")
    )

    val resetLayout = Command(
      "Reset Layout",
      () => state.selection.resetLayout(), // Action to be implemented in ViewerState/SelectionHandler
      selectionNonEmpty,                   // Visible when selection is not empty
      shortcut = None,                     // No shortcut for now
      description = Some("Reset the layout of the selected elements")
    )

    val reverseArrows = Command(
      "Reverse Arrows direction",
      () => state.selection.reverseArrows(), // Action needs implementation in SelectionHandler
      onlyArrowSelected,                     // Visible only when arrows are selected
      description = Some("Reverse the direction of the selected arrows")
    )

    val reverseArrowsStyle = Command(
      "Reverse Arrows Head/Tail Style",
      () => state.selection.reverseArrowsStyle(), // Action needs implementation in SelectionHandler
      onlyArrowSelected,                          // Visible only when arrows are selected
      shortcut = Some(Shortcut("r")),             // Shortcut 'r'
      description = Some("Reverse the Head/Tail *Style* of the selected arrows")
    )

    // ── Keyboard navigation (KeyboardNavOps) ──────────────────────────────
    // The four arrow keys walk the diagram from the selected element, in
    // SCREEN directions. One shared description; the palette shows all four
    // but users discover this with the keys themselves.
    private def navCommand(label: String, key: String, dir: NavDirection, cellDelta: Int) =
      Command(
        label,
        // With a record CELL selected, arrows walk the record's cells instead
        // of the graph (wrapping at the ends).
        () => if !state.recordCells.moveCell(cellDelta) then state.keyboardNav.navigate(dir),
        shortcut = Some(Shortcut(key)),
        description = Some(
          s"$label: follow an arrow from the selected element " +
            "(a lone match jumps through; several select the arrow first — " +
            "perpendicular keys pick among them, the same key continues)"
        )
      )
    val navigateLeft  = navCommand("Navigate left", "ArrowLeft", NavDirection.NavLeft, cellDelta = -1)
    val navigateRight = navCommand("Navigate right", "ArrowRight", NavDirection.NavRight, cellDelta = 1)
    val navigateUp    = navCommand("Navigate up", "ArrowUp", NavDirection.NavUp, cellDelta = -1)
    val navigateDown  = navCommand("Navigate down", "ArrowDown", NavDirection.NavDown, cellDelta = 1)

    // ── Record cells (RecordCellOps) ──────────────────────────────────────
    // Structured record-label editing: the cell selection level. Buttons live
    // in the context strip; the shortcuts mirror the record syntax itself.
    val insertCellAfter = Command(
      "Insert cell after",
      () => state.recordCells.insertSibling(after = true),
      cellSelected,
      shortcut = Some(Shortcut("|")),
      description = Some("Insert an empty record cell after the selected cell")
    )

    val insertCellBefore = Command(
      "Insert cell before",
      () => state.recordCells.insertSibling(after = false),
      cellSelected,
      description = Some("Insert an empty record cell before the selected cell")
    )

    val splitCell = Command(
      "Split cell",
      () => state.recordCells.splitSelectedCell(),
      cellSelected,
      description = Some("Split the selected cell perpendicular to its group ({…} nesting)")
    )

    val removeCell = Command(
      "Remove cell",
      () => { state.recordCells.removeSelectedCell(); () },
      cellSelected,
      description = Some("Remove the selected record cell")
    )

    val editRecordLabelRaw = Command(
      "Edit record label (raw)",
      () => state.selection.editSelectedLabel(),
      canSplitRecordVisible,
      description = Some("Edit the record's whole label as raw record syntax")
    )

  object headers:
    val common       = "Common"
    val navigation   = "Navigation"
    val add          = "Add"
    val select       = "Select"
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

  val byHeader: VectorMap[String, List[Command[?]]] = VectorMap(
    common -> List(
      all.newNode,
      all.newBackwardsNode,
      all.changeProjectName,
      all.moveToGroup,
      routerCmds.createProject,
      routerCmds.navigateHome
    ),
    navigation -> List(
      all.navigateLeft,
      all.navigateRight,
      all.navigateUp,
      all.navigateDown
    ),
    add -> List(
      all.newNode,
      all.newBackwardsNode
    ),
    select -> List(
      all.selectAll,
      all.selectAllNodes,
      all.selectAllArrows,
      all.selectAllGroups,
      all.selectGroupMembers,
      all.selectAllSuccessors,
      all.selectDirectSuccessors,
      all.selectAllPredecessors,
      all.selectDirectPredecessors
    ),
    selection -> List(
      all.group,
      all.ungroup,
      all.moveToGroup,
      all.zoomIntoGroup,
      all.toggleCollapseGroup,
      all.collapseSelectedGroups,
      all.expandSelectedGroups,
      all.editLabel,
      all.hideSelection,
      all.keep,
      all.delete,
      all.duplicate,
      all.combineIntoRecord,
      all.splitRecord,
      all.transposeRecord,
      all.insertCellBefore,
      all.insertCellAfter,
      all.splitCell,
      all.removeCell,
      all.editRecordLabelRaw,
      all.reverseArrows,
      all.reverseArrowsStyle,
      all.resetSelectionAttributes,
      all.clearSelection
//      all.resetLayout
    ),
    successors -> List(
      all.showAllSuccessors,
      all.showDirectSuccessors,
      all.hideSuccessorsRecursive,
      all.hideSuccessorLayer,
      all.toggleSuccessors
    ),
    predecessors -> List(
      all.showAllPredecessors,
      all.showDirectPredecessors,
      all.hidePredecessorsRecursive,
      all.hidePredecessorLayer,
      all.togglePredecessors
    ),
    view -> List(
      all.findElements,
      all.rootsOnly,
      all.showAll,
      all.hideAllNodes,
      all.collapseAllGroups,
      all.expandAllGroups,
      all.toggleLayoutAnimation,
      all.deleteHiddenElements
    ),
    document -> List(
      all.changeProjectName,
      routerCmds.createProject
    ),
    exportAs -> List(
      all.copyAsSVG,
      all.exportAsSVG,
      all.exportAsDOT,
      all.exportAsJSON,
      all.copyShareURL
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
      all.helpKeyboardShortcuts,
      all.openAboutDialog
    ),
    developer -> List(
      all.printVisibleGraphToConsole,
      all.printVisibleGraphJsonToConsole,
      all.printVisibleDOTtoConsole,
      all.printVisibleSimpleGraphJSONtoConsole,
      all.printSelectionToConsole
    )
  )

  /** The toolbar dropdowns, DERIVED from the palette sections above so a new
    * command registered in `byHeader` reaches the menus without a second,
    * hand-curated list to remember (the menus filter by each command's own
    * visibility predicate, so over-inclusion is harmless).
    */
  object sections:
    val add      = byHeader(headers.add)
    val select   = byHeader(headers.select)
    val actions  = byHeader(headers.selection) ++ byHeader(headers.view) ++
      byHeader(headers.successors) ++ byHeader(headers.predecessors)
    val exportAs = byHeader(headers.exportAs)

  // Normalize shortcut matching to reduce layout-specific hacks:
  //  - Letters: compare case-insensitively by lowercasing the key, but keep `shift` to allow distinct bindings (e.g., b vs B).
  //  - Single non-alphanumeric symbols (e.g., '+', '-', '.', ','): ignore `shift` in matching, since key already encodes the symbol.
  private def normalizeShortcut(sh: Shortcut): Shortcut =
    val keyLower = if sh.key.length == 1 && sh.key.head.isLetter then sh.key.toLowerCase else sh.key
    val isSymbol = sh.key.length == 1 && !sh.key.head.isLetterOrDigit
    if isSymbol then sh.copy(key = keyLower, shift = false) else sh.copy(key = keyLower)

  val byShortcut: Map[Shortcut, Command[?]] =
    byHeader.values.flatten
      .collect { case c @ Command(shortcut = Some(sh)) => normalizeShortcut(sh) -> c }
      .toMap

  def handleKeyDown(ev: dom.KeyboardEvent): Unit =
    dom.console.debug("Key pressed:", ev.key, ev.code, ev.shiftKey, ev.metaKey, ev.altKey, ev.ctrlKey)
    dom.console.debug("activeElement:", dom.document.activeElement)

    val isSaveShortcut =
      ev.key.equalsIgnoreCase("s") && (ev.metaKey || ev.ctrlKey) && !ev.altKey
    if isSaveShortcut then
      ev.preventDefault()
      ev.stopPropagation()
      val bridge = js.Dynamic.global.window.selectDynamic("__graphExplorerDesktopBridge")
      val saveCurrentTextFn = bridge.selectDynamic("saveCurrentText")
      if !js.isUndefined(saveCurrentTextFn) && js.typeOf(saveCurrentTextFn) == "function" then
        saveCurrentTextFn.asInstanceOf[js.Function0[Any]]()
      else
        state.infoBus.emit("Desktop save is unavailable in this mode")
      return

    val sh = normalizeShortcut(Shortcut(ev.key, ev.shiftKey, ev.metaKey, ev.altKey, ev.ctrlKey))
    // A shortcut only fires — and only CONSUMES the key — when its command is
    // applicable (same isVisible gate the palette uses). Swallowing keys for
    // inapplicable commands broke browser defaults: bare arrow keys with an
    // empty selection used to kill scrolling of the focused canvas pane.
    for cmd <- byShortcut.get(sh) if cmd.isVisible(state.selection.now()) do
      // Prevent default for all handled shortcuts so the pressed key
      // does not leak into newly-focused inputs (e.g. New Node label dialog)
      ev.preventDefault()
      ev.stopPropagation()
      cmd.execute(logEvent = !ev.repeat)
