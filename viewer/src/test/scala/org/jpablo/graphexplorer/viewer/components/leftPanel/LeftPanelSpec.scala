package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.library.{BrowserLibrary, Library, LibraryIndex, LoggedChecks}
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, DiagramSnapshot, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.jpablo.graphexplorer.viewer.utils.Waits.{after, settle}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The library list of the left panel in the web app: one row for each
  * diagram of the browser library.
  */
class LeftPanelSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "LeftPanelSpec"

  override def munitFixtures = List(mockStorageFixture())

  /** The source of the open diagram. The diagram has the default name, so its
    * row shows the title that the source declares.
    */
  private def openSource(title: String): String =
    s"digraph G {\n  label=\"$title\"\n  a\n}"

  // The index has no CanEqual. The checks below compare two indexes.
  private given CanEqual[LibraryIndex, LibraryIndex] = CanEqual.derived

  private val bravoSource   = "digraph G {\n  b\n}"
  private val charlieSource = "digraph G {\n  c\n}"

  /** The left panel in the document, with three diagrams in the library.
    * `state` shows the diagram `open`.
    */
  private final class Panel(
      val state:   ViewerState,
      val box:     dom.Element,
      val open:    DiagramId,
      val bravo:   DiagramId,
      val charlie: DiagramId
  ):

    /** The rows of the library list, in the order of the page. */
    def rows: Seq[dom.Element] = box.querySelectorAll("#left-panel ul.menu > li").toSeq

    def titles: Seq[String] = rows.map(_.textContent.trim)

    /** The row that shows `title`. */
    def row(title: String): Option[dom.Element] = rows.find(_.textContent.trim == title)

  /** Make three diagrams in an empty browser library, show the left panel of
    * the first one, and run `body` after the view loads. The end of the test
    * removes the panel and the diagrams.
    */
  private def withPanel(body: Panel => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div")
    BrowserLibrary.indexNow().diagrams.foreach(d => BrowserLibrary.deleteDiagram(d.id))
    val open    = BrowserLibrary.createDiagram(DiagramSnapshot.defaultDiagramName, openSource("Open one"))
    val bravo   = BrowserLibrary.createDiagram("Bravo", bravoSource)
    val charlie = BrowserLibrary.createDiagram("Charlie", charlieSource)
    withGraphvizAsync: graphviz =>
      val router   = Router()
      val state    = ViewerState(ViewTarget.Diagram(open), graphviz)(using owner)
      val commands = Commands(state, RouterCommands(router))
      dom.document.body.appendChild(box)
      val root  = render(box, LeftPanel(state, router, commands))
      val panel = Panel(state, box, open, bravo, charlie)
      // A wait after the load, so that a later write of the index gives a new `lastModified`.
      settle(state.loadStatus.now() != DiagramLoadStatus.Loading)
        .flatMap(_ => after(20))
        .flatMap: _ =>
          check(!Library.isDesktop, "precondition: the library is not the browser library")
          same(
            panel.titles.sorted,
            Seq("Bravo", "Charlie", "Open one"),
            "precondition: the list does not show the three diagrams"
          )
          body(panel)
        .transform: outcome =>
          root.unmount()
          owner.killSubscriptions()
          Seq(open, bravo, charlie).foreach(BrowserLibrary.deleteDiagram)
          outcome
    .transform: outcome =>
      if box.parentNode != null then box.parentNode.removeChild(box)
      outcome

  /** The index with each `lastModified` of `id` set to 0. */
  private def withoutLastModifiedOf(index: LibraryIndex, id: DiagramId): LibraryIndex =
    index.copy(diagrams = index.diagrams.map(d => if d.id == id then d.copy(lastModified = 0L) else d))

  /** The titles of the rows in `before` that are not the same DOM node now. */
  private def rebuilt(panel: Panel, before: Map[String, dom.Element]): Seq[String] =
    before.toSeq.sortBy(_._1).collect:
      case (title, node) if !panel.row(title).exists(_ eq node) => title

  // The list is `children <-- Library.index.combineWithFn(...)`. Each new
  // value of the index makes a new `li` for each diagram. In the web app,
  // each write of a diagram writes the index again with a new `lastModified`
  // (`BrowserLibrary.storeDiagram`). The rows of the other diagrams do not
  // change, so they must keep their DOM nodes.
  //
  // This test writes the diagram Charlie with its stored payload. The write
  // changes only the `lastModified` of Charlie in the index. The rows of the
  // open diagram and of Bravo show nothing from that change.
  //
  // Failure before the fix: the rows "Open one" and "Bravo" are new DOM nodes.
  test("a write that moves only the lastModified of one diagram keeps the rows of the other diagrams"):
    withPanel: panel =>
      val before      = Seq("Open one", "Bravo").flatMap(t => panel.row(t).map(t -> _)).toMap
      val indexBefore = Library.indexNow()
      Library.storeDiagram(panel.charlie, DiagramSnapshot.minimal(Some(charlieSource)).copy(name = "Charlie"))
      val indexAfter = Library.indexNow()
      check(indexAfter != indexBefore, "precondition: the write did not change the index")
      same(
        withoutLastModifiedOf(indexAfter, panel.charlie),
        withoutLastModifiedOf(indexBefore, panel.charlie),
        "precondition: the write changed more than the lastModified of Charlie"
      )
      afterMicrotasks:
        same(panel.titles.sorted, Seq("Bravo", "Charlie", "Open one"), "the list does not show the three diagrams")
        same(
          rebuilt(panel, before),
          Nil,
          "a change of the lastModified of Charlie replaced the rows of other diagrams with new DOM nodes"
        )

  // The same rebuild, from a keystroke in the source of the open diagram. The
  // edit writes the payload, and thus the index, at once. The title of the
  // open row follows the source live. The rows of Bravo and Charlie do not
  // change, so they must keep their DOM nodes.
  //
  // Failure before the fix: the rows "Bravo" and "Charlie" are new DOM nodes.
  test("a keystroke in the open diagram updates its title and keeps the rows of the other diagrams"):
    withPanel: panel =>
      val before      = Seq("Bravo", "Charlie").flatMap(t => panel.row(t).map(t -> _)).toMap
      val indexBefore = Library.indexNow()
      panel.state.sourceText.set(openSource("Open two"))
      afterMicrotasks:
        val indexAfter = Library.indexNow()
        check(indexAfter != indexBefore, "precondition: the keystroke did not write the index")
        same(
          withoutLastModifiedOf(indexAfter, panel.open),
          withoutLastModifiedOf(indexBefore, panel.open),
          "precondition: the keystroke changed more than the lastModified of the open diagram"
        )
        same(
          panel.titles.sorted,
          Seq("Bravo", "Charlie", "Open two"),
          "the open row does not show the new title of the source"
        )
        check(
          panel.row("Open two").exists(_.querySelector("a.gx-row-active") != null),
          "the open row does not show the active mark"
        )
        same(
          rebuilt(panel, before),
          Nil,
          "a keystroke in the open diagram replaced the rows of other diagrams with new DOM nodes"
        )

  // The rows keep the order of the creation time, the newest first. A new
  // diagram gets a new row at the top, and a deleted diagram loses its row.
  // The rows of the other diagrams keep their DOM nodes.
  //
  // The test does not check the title of the new row. `displayNames` reads
  // the payload when the set of ids changes. `createDiagram` writes the index
  // entry before the payload, so that read finds no payload.
  //
  // Failure: the order is not the order of the creation time, a row is
  // missing or extra, or the other rows are new DOM nodes.
  test("a new diagram goes to the top of the list, and a deleted diagram leaves it"):
    withPanel: panel =>
      // The setup makes "Open one", Bravo and Charlie in this order. Thus
      // Charlie is the newest.
      same(panel.titles, Seq("Charlie", "Bravo", "Open one"), "precondition: the list is not the newest first")
      val before = Seq("Charlie", "Open one").flatMap(t => panel.row(t).map(t -> _)).toMap
      val delta  = BrowserLibrary.createDiagram("Delta", "digraph G {\n  d\n}")
      afterMicrotasks(())
        .map: _ =>
          same(panel.titles.size, 4, "the new diagram did not get a row")
          same(panel.titles.tail, Seq("Charlie", "Bravo", "Open one"), "the new diagram is not at the top")
          BrowserLibrary.deleteDiagram(panel.bravo)
        .flatMap(_ => afterMicrotasks(()))
        .map: _ =>
          same(panel.titles.tail, Seq("Charlie", "Open one"), "the deleted diagram did not leave the list")
          same(rebuilt(panel, before), Nil, "an add and a delete replaced the rows of other diagrams")
        .transform: outcome =>
          BrowserLibrary.deleteDiagram(delta)
          outcome
