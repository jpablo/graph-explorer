package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.keys.EventProp
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom.window
import org.jpablo.graphexplorer.viewer.models.NodeId

case class CommandEntry(
    title:         String,
    action:        EventProp[dom.MouseEvent] => Modifier[ReactiveHtmlElement.Base],
    fromSelection: Set[NodeId] => Boolean = _.nonEmpty,
    shortcut:      Option[String] = None
)

case class CommandGroup(
    title:   String,
    entries: List[CommandEntry]
)

class Commands(state: ViewerState):
  import state.eventHandlers.*

  private val always = (_: Any) => true

  private val copyToClipboard = window.navigator.clipboard.writeText

  val menuSections = List(
    CommandGroup(
      "Common",
      List(
        CommandEntry("Add node", _ --> state.addNode(), always, shortcut = Some("n"))
      )
    ),
    CommandGroup(
      "selection",
      List(
        CommandEntry("Hide", hideSelectedNodes, shortcut             = Some("h")),
        CommandEntry("Hide others", hideNonSelectedNodes, shortcut = Some("Shift+h")),
        CommandEntry("Delete", deleteSelectedNodes, shortcut         = Some("Del")),
        CommandEntry("Group", groupSelectedNodes, shortcut           = Some("g")),
        CommandEntry("Clear selection", clearSelection, shortcut     = Some("Esc")),
        CommandEntry("Copy as SVG", _.copySelectionAsSVG(copyToClipboard), shortcut = Some("c"))
      )
    ),
    CommandGroup(
      "successors",
      List(
        CommandEntry("Show all successors", showAllSuccessors),
        CommandEntry("Show direct successors", showDirectSuccessors),
        CommandEntry("Select all successors", selectSuccessors),
        CommandEntry("Select direct successors", selectDirectSuccessors)
      )
    ),
    CommandGroup(
      "predecessors",
      List(
        CommandEntry("Show all predecessors", showAllPredecessors),
        CommandEntry("Show direct predecessors", showDirectPredecessors),
        CommandEntry("Select all predecessors", selectPredecessors),
        CommandEntry("Select direct predecessors", selectDirectPredecessors)
      )
    ),
    CommandGroup(
      "View",
      List(
        CommandEntry("Roots only", keepRootsOnly, always),
        CommandEntry("Show all", _ --> state.showAllNodes(), always),
        CommandEntry("Hide all", hideAllNodes, always)
      )
    ),
    CommandGroup(
      "Export",
      List(
        CommandEntry("as SVG", _.copyAsFullDiagramSVG(copyToClipboard), always),
        CommandEntry("as DOT", _.copyAsDOT(copyToClipboard), always),
        CommandEntry("as JSON DOT AST", _.copyAsJSON(copyToClipboard), always)
      )
    ),
    CommandGroup(
      "Zoom",
      List(
        CommandEntry("Zoom out", _ --> state.zoomValue.update(_ * 0.9), always),
        CommandEntry("Fit", _ --> state.fitDiagram.emit(()), always),
        CommandEntry("Zoom in", _ --> state.zoomValue.update(_ * 1.1), always)
      )
    ),
    CommandGroup(
      "Undo/Redo",
      List(
        CommandEntry("Undo", _ --> state.undoEvent.emit(()), always),
        CommandEntry("Redo", _ --> state.redoEvent.emit(()), always)
      )
    )
  )
