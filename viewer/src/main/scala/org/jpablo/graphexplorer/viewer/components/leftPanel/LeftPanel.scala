package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.library.{DiagramSummary, Library, LibraryIndex, LibraryNavigation}
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.Commands
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.widgets.IconButton

/** The titles to show for the diagrams in the library. The rule is the same as on the
  * library page: the stored name, or the title that the diagram declares while it has no name.
  *
  * The key is the SET of diagram ids, not the value of the library index. Each keystroke
  * writes the index again (a new lastModified). Each name costs a payload read and a title
  * scan. Only the title of the open diagram can change while a person types, and the row
  * of the open diagram reads that title directly from `state.displayTitle`.
  */
private def displayNames(state: ViewerState, index: Signal[LibraryIndex]): Signal[Map[DiagramId, String]] =
  index
    .map(_.diagrams.map(_.id))
    .distinct
    .map: ids =>
      ids.flatMap(id => Library.diagramCardInfo(id, state.languages).map(id -> _.displayName)).toMap

/** The diagram of the library that the view shows. On the desktop, the view
  * shows a file, and the library knows the file by its path.
  */
private def openId(state: ViewerState, index: Signal[LibraryIndex]): Signal[Option[DiagramId]] =
  state.target match
    case ViewTarget.File(_) =>
      state.documentSession.combineWith(index).map: (session, _) =>
        session.flatMap(s => Library.desktop.flatMap(_.idOfPath(s.path)))
    case _ => Signal.fromValue(Some(state.diagramId))

def LeftPanel(state: ViewerState, router: Router, commands: Commands) =
  // One signal of each for all the rows. On the desktop, `Library.index` makes a new
  // signal for each call, and each one builds the index again.
  val index = Library.index
  val names = displayNames(state, index)
  val open  = openId(state, index).distinct

  /** One row of the library list. The title and the mark of the row follow the signals,
    * so a change does not make the row again.
    *
    * The title of the open diagram can change while a person types, so the open row reads
    * it live from `state.displayTitle`. The other rows read the snapshot of `displayNames`.
    */
  def libraryRow(id: DiagramId, diagram: Signal[DiagramSummary]): HtmlElement =
    val isOpen = open.map(_.contains(id)).distinct
    val shown = isOpen.combineWithFn(state.displayTitle, names, diagram): (isOpen, title, names, diagram) =>
      if isOpen then title else names.getOrElse(id, diagram.name)
    li(
      a(
        cls := "flex items-center gap-2",
        // Not daisyUI's `menu-active`: that inverts the row to near-black, which
        // shouts where the rest of the chrome marks "current" with a quiet tint.
        cls("gx-row-active") <-- isOpen,
        div(cls := "truncate", text <-- shown.distinct),
        onClick --> LibraryNavigation.open(router, id, state.errorBus.emit)
      )
    )

  div(
    cls := "border-r border-base-300",
    // Panel content
    div(
      idAttr := "left-panel",
      cls <-- state.leftPanelVisible.signal.map(if _ then "w-[16rem] opacity-100 visible"
      else "w-0 opacity-0 invisible"),
      styleAttr <-- state.leftPanelVisible.signal.map(visible =>
        if visible then "--left-panel-width: 16rem; --left-panel-border-width: 0px;"
        else "--left-panel-width: 0px; --left-panel-border-width: 0px;"
      ),

      // Header section with margin-top to accommodate selection sidebar
      div(
        cls := "header-section flex flex-col h-full",
        div(
          cls := "flex items-center border-b border-base-300 px-2 pb-1",
          div(
            cls := "flex items-center justify-between w-full ml-2 mt-1.5",
            h2(
              // Same weight and size as the right panel's heading: the two panels frame the
              // canvas, so they should read as a matched pair rather than two designs.
              cls := "text-lg font-semibold flex-1",
              "Library"
            ),
            IconButton("bi-plus-circle", "Create Diagram")(
              commands.routerCmds.createDiagram.execute()
            )
          )
        ),

        // The list of the diagrams. It takes the rest of the height.
        div(
          cls := "grow overflow-y-auto",
          ul(
            cls := "menu menu-sm w-full",
            // The split keys the rows by the diagram id. A row keeps its DOM node while its
            // diagram stays in the list. Each keystroke writes the index again (a new
            // lastModified), and before the split, each write made all the rows again.
            // A row shows only the id and the name, so a change of the order, the ids or
            // the names is the only change that gets to the split.
            children <-- index
              .map(_.diagrams.sortBy(-_.creationTime))
              .distinctBy(_.map(d => (d.id, d.name)))
              .split(_.id): (id, _, diagram) =>
                libraryRow(id, diagram)
          )
        )
      )
    )
  )
