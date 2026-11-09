package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.viewer.desktop.DesktopDocumentRegistry
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The browser library goes onto the disk, once: each browser diagram becomes
  * a new file in the folder for new diagrams, and an entry (plan §4). There is
  * no binding, because the file is the diagram.
  */
class DesktopMigrationSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "DesktopMigrationSpec"

  override def munitFixtures = List(mockStorageFixture())

  override def afterEach(context: AfterEach): Unit =
    Library.restoreDefault()
    DesktopDocumentRegistry.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private def raw(key: String) = s"[StoredString]$key"

  /** A browser library with these diagrams: (id, name, source). */
  private def browserLibrary(diagrams: (String, String, String)*): Unit =
    val index = diagrams
      .map((id, name, _) => s"""{"id":{"value":"$id"},"name":"$name","lastModified":200,"createdAt":100}""")
      .mkString("""{"projects":[""", ",", "]}")
    dom.window.localStorage.setItem(raw("graph-explorer.projects"), index)
    diagrams.foreach: (id, name, source) =>
      dom.window.localStorage.setItem(
        raw(s"graph-explorer.project.$id"),
        s"""{"projectName":"$name","source":${ujson.Str(source).render()},"format":"DOT","hiddenElements":{"ids":[{"$$type":"NodeId","value":"a"}]}}"""
      )

  private def withLibrary[A](f: (FakeDesktopShell, DesktopLibrary) => Future[A]): Future[A] =
    val shell = FakeDesktopShell()
    shell.install()
    DesktopLibrary
      .load()
      .flatMap(library => f(shell, library).transform { r => library.close(); r })
      .transform { r => shell.uninstall(); r }

  private def localStorageNow: Map[String, String] =
    (0 until dom.window.localStorage.length).toList
      .flatMap(i => Option(dom.window.localStorage.key(i)))
      .map(k => k -> dom.window.localStorage.getItem(k))
      .toMap

  test("each browser diagram becomes a file in the folder for new diagrams, with an entry"):
    browserLibrary(("p1", "Architecture", "digraph G { a }"), ("p2", "Notes", "digraph G { n }"))
    withLibrary: (shell, library) =>
      DesktopMigration.runOnce(library).map: report =>
        val arch = s"${shell.folder}/Architecture.dot"
        same(shell.files.get(arch), Some("digraph G { a }"))
        same(shell.files.get(s"${shell.folder}/Notes.dot"), Some("digraph G { n }"))
        same(shell.entryOf(arch).map(_.metadata.hiddenElements), Some(Set("node:a")), "the view state did not come with the diagram")
        check(library.diagramExists(DiagramId(shell.keyOf(arch))), "the copy is not in the library")
        check(report.exists(_.exists(_.contains(arch))), s"the report does not name the file: $report")
        check(DesktopMigration.hasRun, "the copy is not marked done")

  test("the browser library stays as it is"):
    browserLibrary(("p1", "Architecture", "digraph G { a }"))
    val before = localStorageNow
    withLibrary: (_, library) =>
      DesktopMigration.runOnce(library).map: _ =>
        same(localStorageNow.removed(raw("graph-explorer.migrated-to-disk")), before)

  test("a second run copies nothing"):
    browserLibrary(("p1", "Architecture", "digraph G { a }"))
    withLibrary: (shell, library) =>
      DesktopMigration
        .runOnce(library)
        .flatMap: _ =>
          shell.calls.clear()
          DesktopMigration.runOnce(library)
        .map: report =>
          same(report, None)
          same(shell.callsOf("create_document").size, 0)

  test("a run after a crash finds the files and the entries of the first run, and changes no entry"):
    browserLibrary(("p1", "Architecture", "digraph G { a }"))
    val shell = FakeDesktopShell()
    val path  = s"${shell.folder}/Architecture.dot"
    shell.files(path) = "digraph G { a }"
    // The person tagged the copy after the first run, which crashed before
    // it marked the copy done.
    shell.putEntry(LibraryEntry(path, DiagramMetadata(name = "Architecture", tags = List("mine")), 1, 1))
    shell.install()
    DesktopLibrary
      .load()
      .flatMap: library =>
        DesktopMigration.runOnce(library).map: _ =>
          library.close()
          same(shell.callsOf("create_document").size, 0, "the run made a copy of the file of the first run")
          same(shell.callsOf("library_write").size, 0, "the run wrote over an entry")
          same(shell.entryOf(path).map(_.metadata.tags), Some(List("mine")))
      .transform { r => shell.uninstall(); r }

  test("a failure leaves the copy not done, so the next start tries again"):
    browserLibrary(("p1", "Architecture", "digraph G { a }"))
    withLibrary: (shell, library) =>
      shell.refusals("create_document") = ("DOCUMENT_DENIED", "path is blocked by denylist")
      DesktopMigration.runOnce(library).map: report =>
        check(!DesktopMigration.hasRun, "a failed copy is marked done")
        check(report.exists(_.exists(_.contains("denylist"))), s"the report does not say why: $report")
        same(shell.entries.toMap, Map.empty[String, String])

  test("an empty browser library copies nothing, and the copy is marked done"):
    withLibrary: (shell, library) =>
      DesktopMigration.runOnce(library).map: report =>
        same(report, None)
        same(shell.callsOf("create_document").size, 0)
        check(DesktopMigration.hasRun, "the empty copy is not marked done")
