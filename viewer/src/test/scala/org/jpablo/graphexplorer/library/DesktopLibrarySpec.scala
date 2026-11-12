package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramMetadata, LibraryEntry}
import com.raquo.laminar.api.L.unsafeWindowOwner
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.RouterCommands
import org.jpablo.graphexplorer.viewer.backends.{DefaultDiagramLanguages, DiagramFormat, DiagramLanguages}
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.desktop.DesktopDocumentRegistry
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.DiagramSnapshot
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The desktop library: the metadata store, the files behind it, and the
  * operations on a file (ADR 0001, plan §5 phase 3a), against a fake shell.
  */
class DesktopLibrarySpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "DesktopLibrarySpec"

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit = DesktopDocumentRegistry.reset()

  override def afterEach(context: AfterEach): Unit =
    Library.restoreDefault()
    DesktopDocumentRegistry.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private def entry(path: String, tags: List[String] = Nil, hidden: Set[String] = Set.empty, at: Long = 1) =
    LibraryEntry(path, DiagramMetadata(tags = tags, hiddenElements = hidden), at, at)

  private def withShell[A](f: FakeDesktopShell => Future[A]): Future[A] =
    val shell = FakeDesktopShell()
    shell.install()
    f(shell).transform { result => shell.uninstall(); result }

  /** The backends, for the title and the kind of a card. */
  private def withLanguages[A](f: DiagramLanguages => Future[A]): Future[A] =
    Graphviz.build().flatMap(graphviz => f(DefaultDiagramLanguages(graphviz)))

  private def idOf(shell: FakeDesktopShell, path: String) = DiagramId(shell.keyOf(path))

  // ------------------------------------------------------------- the list

  test("the library is the entries of the metadata store"):
    withShell: shell =>
      shell.files("/w/a.dot") = "digraph { a }"
      shell.putEntry(entry("/w/a.dot"))
      DesktopLibrary.load().map: library =>
        same(library.indexNow().diagrams.map(_.name), List("a"))
        check(library.diagramExists(idOf(shell, "/w/a.dot")), "the id of a diagram is the key of its entry")

  /** V-15. A diagram that `gx` put in the library while no desktop ran shows
    * in the UI when a desktop starts.
    */
  test("V-15: an entry that gx wrote while the app was closed is there"):
    withShell: shell =>
      shell.files("/w/from-gx.dot") = "digraph { gx }"
      shell.putEntry(entry("/w/from-gx.dot"))
      DesktopLibrary.load().map: library =>
        same(library.indexNow().diagrams.map(_.name), List("from-gx"))

  test("V-15: an entry that gx adds while the app is open appears on refresh"):
    withShell: shell =>
      DesktopLibrary.load().flatMap: library =>
        same(library.indexNow().diagrams.map(_.name), Nil)
        shell.files("/w/late.dot") = "digraph { l }"
        shell.putEntry(entry("/w/late.dot"))
        library.refresh().map: _ =>
          same(library.indexNow().diagrams.map(_.name), List("late"))

  test("the event of the shell, ge:library.changed, reads the store again"):
    withShell: shell =>
      DesktopLibrary.load().flatMap: library =>
        shell.files("/w/late.dot") = "digraph { l }"
        shell.putEntry(entry("/w/late.dot"))
        org.scalajs.dom.window.dispatchEvent(new org.scalajs.dom.Event("ge:library.changed"))
        afterMicrotasks:
          library.close()
          same(library.indexNow().diagrams.map(_.name), List("late"), "the event did not refresh the mirror")

  test("an entry that does not parse is skipped, not fatal to the whole library"):
    withShell: shell =>
      shell.files("/w/good.dot") = "digraph { g }"
      shell.putEntry(entry("/w/good.dot"))
      shell.entries(shell.entryNameOf("/w/bad.dot")) = "{ this is not an entry"
      DesktopLibrary.load().map: library =>
        same(library.indexNow().diagrams.map(_.name), List("good"))

  test("an entry whose file name is not the key of its path is skipped, as the JVM store skips it"):
    withShell: shell =>
      shell.files("/w/copy.dot") = "digraph { c }"
      shell.entries(shell.entryNameOf("/w/other.dot")) = upickle.default.write(entry("/w/copy.dot"))
      DesktopLibrary.load().map: library =>
        same(library.indexNow().diagrams.map(_.name), Nil, "a copy of an entry under a wrong name is in the list")

  test("the name of a card is the title in the source, or else the file name"):
    withShell: shell =>
      shell.files("/w/titled.dot") = "digraph G {\n  label=\"Architecture\"\n  a\n}"
      shell.files("/w/plain.mmd") = "flowchart LR\n  a --> b"
      shell.putEntry(entry("/w/titled.dot", at = 2))
      shell.putEntry(entry("/w/plain.mmd", at = 1))
      withLanguages: languages =>
        DesktopLibrary.load().map: library =>
          same(library.indexNow().diagrams.map(_.name), List("Architecture", "plain"))
          val titled = library.diagramCardInfo(idOf(shell, "/w/titled.dot"), languages)
          same(titled.map(_.displayName), Some("Architecture"))
          val plain = library.diagramCardInfo(idOf(shell, "/w/plain.mmd"), languages)
          same(plain.map(i => (i.displayName, i.format, i.problem)), Some(("plain", DiagramFormat.Mermaid, None)))

  test("the thumbnail of a card comes from the file, and the home page reads it with no watch"):
    withShell: shell =>
      shell.files("/w/a.dot") = "digraph { thumb }"
      shell.putEntry(entry("/w/a.dot"))
      DesktopLibrary.load().flatMap: library =>
        val source = library.diagramSource(idOf(shell, "/w/a.dot")).observe(using unsafeWindowOwner)
        same(source.now(), "digraph { thumb }")
        same(shell.watched.toSet, Set.empty[String], "a read for the home page must not watch the file")
        same(shell.callsOf("read_document").map(_.path.asInstanceOf[String]), List("/w/a.dot"))
        // A different tool changes the file. The home page reads it again
        // when it opens.
        shell.files("/w/a.dot") = "digraph { changed }"
        library.readFiles().map(_ => same(source.now(), "digraph { changed }"))

  test("a missing file shows as missing, with its file name, and stays in the list"):
    withShell: shell =>
      shell.putEntry(entry("/w/gone.dot"))
      withLanguages: languages =>
        DesktopLibrary.load().map: library =>
          same(library.indexNow().diagrams.map(_.name), List("gone"))
          val info = library.diagramCardInfo(idOf(shell, "/w/gone.dot"), languages)
          same(info.map(_.displayName), Some("gone"))
          check(info.flatMap(_.problem).exists(_.contains("missing")), s"the card does not say the file is missing: $info")
          same(library.fileState(idOf(shell, "/w/gone.dot")), Some(FileState.Missing))

  test("Remove from library deletes the entry, and the file stays"):
    withShell: shell =>
      shell.files("/w/a.dot") = "digraph { a }"
      shell.putEntry(entry("/w/a.dot"))
      DesktopLibrary.load().flatMap: library =>
        library.deleteDiagram(idOf(shell, "/w/a.dot"))
        afterMicrotasks:
          check(!library.diagramExists(idOf(shell, "/w/a.dot")), "the mirror still has the diagram")
          check(!shell.entries.contains(shell.entryNameOf("/w/a.dot")), s"the entry is still on the disk: ${shell.entries.keys}")
          same(shell.files.get("/w/a.dot"), Some("digraph { a }"), "Remove from library must not touch the file")

  test("the control says Remove from library on the desktop, and Delete in the web app"):
    same(LibraryNavigation.removeLabel, "Delete diagram")
    withShell: _ =>
      DesktopLibrary.load().map: library =>
        Library.install(library)
        same(LibraryNavigation.removeLabel, "Remove from library")
        check(LibraryNavigation.removeQuestion.contains("file stays"), LibraryNavigation.removeQuestion)

  test("Remove from library works for a missing file"):
    withShell: shell =>
      shell.putEntry(entry("/w/gone.dot"))
      DesktopLibrary.load().flatMap: library =>
        library.deleteDiagram(idOf(shell, "/w/gone.dot"))
        afterMicrotasks:
          same(shell.entries.toMap, Map.empty[String, String])
          same(library.indexNow().diagrams.map(_.name), Nil)

  test("a refresh that started before a change of the page does not undo the change"):
    withShell: shell =>
      shell.files("/w/a.dot") = "digraph { a }"
      shell.putEntry(entry("/w/a.dot"))
      DesktopLibrary.load().flatMap: library =>
        val refreshed = library.refresh()
        // The page removes the entry while the read is on its way.
        library.deleteDiagram(idOf(shell, "/w/a.dot"))
        refreshed.map(_ => check(!library.diagramExists(idOf(shell, "/w/a.dot")), "the refresh brought back a removed entry"))

  // ------------------------------------------------------------ open

  test("a diagram of the library opens through its file: a watch, a document session, and the file route"):
    withShell: shell =>
      shell.files("/w/a.dot") = "digraph { a -> b }"
      shell.putEntry(entry("/w/a.dot"))
      DesktopLibrary.load().flatMap: library =>
        library.open(idOf(shell, "/w/a.dot")).map: result =>
          val session = DesktopDocumentRegistry.find("/w/a.dot")
          check(session.isDefined, "no document session for the file")
          same(result, Right(Route.File(session.get.id.value)))
          same(session.map(_.sourceText), Some("digraph { a -> b }"))
          same(session.map(_.revision), Some(shell.hash("digraph { a -> b }")))
          same(shell.watched.toSet, Set("/w/a.dot"))

  test("a diagram whose file is missing does not open, and the card shows it missing"):
    withShell: shell =>
      shell.files("/w/a.dot") = "digraph { a }"
      shell.putEntry(entry("/w/a.dot"))
      DesktopLibrary.load().flatMap: library =>
        shell.files.remove("/w/a.dot")
        library.open(idOf(shell, "/w/a.dot")).map: result =>
          check(result.left.exists(_.contains("missing")), s"the refusal does not say the file is missing: $result")
          same(DesktopDocumentRegistry.find("/w/a.dot"), None)
          same(library.fileState(idOf(shell, "/w/a.dot")), Some(FileState.Missing))

  // Related to finding 12. An old entry has a path that is not canonical: here
  // `/tmp/...`, which macOS spells `/private/tmp/...`. `openPath` then gives
  // the canonical path the key of the OLD path in the `keys` cache. `gx` later
  // writes a correct entry for the canonical path, but `loadEntries` computes
  // its key from the cache, finds that the name is not that key, and skips the
  // entry with a warning.
  test("open of an entry with a non-canonical path does not hide a later entry for the canonical path"):
    withShell: shell =>
      shell.canonical = p => if p.startsWith("/tmp/") then "/private" + p else p
      val oldPath   = "/tmp/w/a.dot"
      val canonical = "/private/tmp/w/a.dot"
      shell.files(canonical) = "digraph { a }"
      shell.putEntry(entry(oldPath, hidden = Set("node:a")))
      DesktopLibrary.load().flatMap: library =>
        library.open(idOf(shell, oldPath)).flatMap: result =>
          check(result.isRight, s"precondition: the old entry opens: $result")
          same(DesktopDocumentRegistry.find(canonical).map(_.path), Some(canonical), "precondition: the session has the canonical path")
          // `gx tag` of the file writes the entry of the canonical path.
          shell.putEntry(entry(canonical, tags = List("infra"), at = 2))
          library.refresh().map: _ =>
            check(
              library.diagramExists(idOf(shell, canonical)),
              s"the entry that gx wrote for $canonical is not in the list: ${library.indexNow().diagrams.map(_.id.value)}"
            )

  // ----------------------------------------------------------- new diagram

  test("New diagram creates Untitled.dot in the folder for new diagrams, adds an entry, and opens it"):
    withShell: shell =>
      DesktopLibrary.load().flatMap: library =>
        library.newDiagram("Untitled", DiagramSnapshot.minimalGraphText, ".dot").map: result =>
          val path = s"${shell.folder}/Untitled.dot"
          same(shell.files.get(path), Some(DiagramSnapshot.minimalGraphText), "the new file has the empty-diagram text")
          check(shell.entryOf(path).isDefined, s"no entry for the new file: ${shell.entries.keys}")
          check(library.diagramExists(idOf(shell, path)), "the new diagram is not in the list")
          val session = DesktopDocumentRegistry.find(path)
          same(result, Right(Route.File(session.map(_.id.value).getOrElse("?"))))

  test("a second New diagram gives Untitled 2.dot, and never writes over a file"):
    withShell: shell =>
      shell.files(s"${shell.folder}/Untitled.dot") = "digraph { mine }"
      DesktopLibrary.load().flatMap: library =>
        library.newDiagram("Untitled", DiagramSnapshot.minimalGraphText, ".dot").map: _ =>
          same(shell.files.get(s"${shell.folder}/Untitled.dot"), Some("digraph { mine }"))
          same(shell.files.get(s"${shell.folder}/Untitled 2.dot"), Some(DiagramSnapshot.minimalGraphText))

  test("New diagram tries the next name when the shell refuses one that exists"):
    // The folder listing can be stale: a different tool can make the file
    // after the listing. The shell refuses it, and the next name is tried.
    withShell: shell =>
      DesktopLibrary.load().flatMap: library =>
        val created = library.newDiagram("Untitled", "digraph { x }", ".dot")
        shell.files(s"${shell.folder}/Untitled.dot") = "digraph { late }"
        created.map: _ =>
          same(shell.files.get(s"${shell.folder}/Untitled.dot"), Some("digraph { late }"))
          same(shell.files.get(s"${shell.folder}/Untitled 2.dot"), Some("digraph { x }"))

  test("New diagram says where the new file is when its entry cannot be written"):
    withShell: shell =>
      DesktopLibrary.load().flatMap: library =>
        shell.refusals("library_write") = ("LIBRARY_NOT_MIGRATED", "the metadata store does not exist")
        library.newDiagram("Untitled", "digraph { x }", ".dot").map: result =>
          val path = s"${shell.folder}/Untitled.dot"
          same(shell.files.get(path), Some("digraph { x }"))
          check(result.left.exists(m => m.contains(path) && m.contains("metadata store")), s"the message is not clear: $result")

  test("on the desktop, the Create Diagram command makes a file and opens it"):
    withShell: shell =>
      DesktopLibrary.load().flatMap: library =>
        Library.install(library)
        val router = Router()
        val errors = scala.collection.mutable.Buffer.empty[String]
        RouterCommands(router, errors += _).createDiagram.execute(logEvent = false)
        val route = router.currentRoute.observe(using unsafeWindowOwner)
        afterMicrotasks(()).flatMap(_ => afterMicrotasks(())).map: _ =>
          val path = s"${shell.folder}/Untitled.dot"
          same(errors.toList, Nil)
          same(shell.files.get(path), Some(DiagramSnapshot.minimalGraphText))
          same(route.now(), Route.File(DesktopDocumentRegistry.find(path).map(_.id.value).getOrElse("?")))

  // ---------------------------------------------------------------- rename

  private def openFile(library: DesktopLibrary, shell: FakeDesktopShell, path: String) =
    library.open(idOf(shell, path)).map(_ => DesktopDocumentRegistry.find(path).getOrElse(fail(s"no session for $path")))

  test("Rename renames the file, moves its entry to the new key, and the session follows"):
    withShell: shell =>
      shell.files("/w/a.dot") = "digraph { a }"
      shell.putEntry(entry("/w/a.dot", tags = List("infra"), hidden = Set("node:a")))
      DesktopLibrary.load().flatMap: library =>
        openFile(library, shell, "/w/a.dot").flatMap: session =>
          library.renameFile(session.id, "b").map: result =>
            same(result, FileRename.Done("/w/b.dot"))
            same(shell.files.get("/w/b.dot"), Some("digraph { a }"))
            check(!shell.files.contains("/w/a.dot"), "the old file is still there")
            same(shell.entryOf("/w/b.dot").map(e => (e.metadata.tags, e.metadata.hiddenElements)), Some((List("infra"), Set("node:a"))))
            same(shell.entryOf("/w/a.dot"), None, "the old entry is still there")
            same(DesktopDocumentRegistry.get(session.id).map(_.path), Some("/w/b.dot"), "the session did not follow the file")
            check(library.diagramExists(idOf(shell, "/w/b.dot")), "the list does not have the new name")
            check(!library.diagramExists(idOf(shell, "/w/a.dot")), "the list still has the old name")

  test("Rename is refused when the new name exists, and nothing changes"):
    withShell: shell =>
      shell.files("/w/a.dot") = "digraph { a }"
      shell.files("/w/b.dot") = "digraph { b }"
      shell.putEntry(entry("/w/a.dot", tags = List("t")))
      DesktopLibrary.load().flatMap: library =>
        openFile(library, shell, "/w/a.dot").flatMap: session =>
          library.renameFile(session.id, "b.dot").map: result =>
            check(result match { case FileRename.Refused(m) => m.contains("exists"); case _ => false }, s"not refused: $result")
            same(shell.files.get("/w/a.dot"), Some("digraph { a }"))
            same(shell.files.get("/w/b.dot"), Some("digraph { b }"))
            same(shell.entryOf("/w/a.dot").map(_.metadata.tags), Some(List("t")))
            same(DesktopDocumentRegistry.get(session.id).map(_.path), Some("/w/a.dot"))

  // Finding 5. The rename dialog starts with the full file name, and Enter
  // sends it. The shell gives Ok with the same path, so the old key and the
  // new key are one key. `moveEntry` writes the entry and then removes it: the
  // diagram leaves the library with its tags and its hidden set, and the
  // result is `Done` with no message.
  test("Rename to the same name keeps the file and its entry"):
    withShell: shell =>
      shell.files("/w/a.dot") = "digraph { a }"
      shell.putEntry(entry("/w/a.dot", tags = List("infra"), hidden = Set("node:a")))
      DesktopLibrary.load().flatMap: library =>
        openFile(library, shell, "/w/a.dot").flatMap: session =>
          library.renameFile(session.id, session.baseName).map: result =>
            same(session.baseName, "a.dot", "precondition: the dialog sends the full file name")
            same(result, FileRename.Done("/w/a.dot"))
            same(shell.files.get("/w/a.dot"), Some("digraph { a }"), "the file changed")
            same(
              shell.entryOf("/w/a.dot").map(e => (e.metadata.tags, e.metadata.hiddenElements)),
              Some((List("infra"), Set("node:a"))),
              "a rename to the same name removed the entry from the disk"
            )
            check(library.diagramExists(idOf(shell, "/w/a.dot")), "a rename to the same name removed the diagram from the list")

  test("Rename of a file with no entry renames only the file"):
    withShell: shell =>
      shell.files("/w/loose.dot") = "digraph { l }"
      DesktopLibrary.load().flatMap: library =>
        val session = DesktopDocumentRegistry.record("/w/loose.dot", shell.hash("digraph { l }"), "digraph { l }")
        library.renameFile(session.id, "kept").map: result =>
          same(result, FileRename.Done("/w/kept.dot"))
          same(shell.entries.toMap, Map.empty[String, String], "a rename must not put the file in the library")

  test("Rename says so clearly when the file moved and its entry did not"):
    withShell: shell =>
      shell.files("/w/a.dot") = "digraph { a }"
      shell.putEntry(entry("/w/a.dot", tags = List("t")))
      DesktopLibrary.load().flatMap: library =>
        openFile(library, shell, "/w/a.dot").flatMap: session =>
          shell.refusals("library_write") = ("LIBRARY_WRITE_FAILED", "disk full")
          library.renameFile(session.id, "b").map: result =>
            result match
              case FileRename.MetadataNotMoved(path, message) =>
                same(path, "/w/b.dot")
                check(message.contains("/w/b.dot") && message.contains("disk full"), s"the message is not clear: $message")
              case other => check(false, s"expected MetadataNotMoved, got $other")
            same(shell.files.get("/w/b.dot"), Some("digraph { a }"), "the file rename comes first")
            same(shell.entryOf("/w/a.dot").map(_.metadata.tags), Some(List("t")), "the old entry must stay when the new one fails")

  test("a file name for a rename keeps the extension, and refuses a folder"):
    same(DesktopLibrary.fileNameFor("b", "/w/a.dot"), Right("b.dot"))
    same(DesktopLibrary.fileNameFor("b.gv", "/w/a.dot"), Right("b.gv"))
    same(DesktopLibrary.fileNameFor("  b  ", "C:\\w\\a.mmd"), Right("b.mmd"))
    check(DesktopLibrary.fileNameFor("x/b", "/w/a.dot").isLeft, "a name with a separator")
    check(DesktopLibrary.fileNameFor("   ", "/w/a.dot").isLeft, "an empty name")
    same(DesktopLibrary.siblingOf("/w/a.dot", "b.dot"), "/w/b.dot")
    same(DesktopLibrary.siblingOf("C:\\w\\a.dot", "b.dot"), "C:\\w\\b.dot")
