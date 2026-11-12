package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.LibraryEntry
import org.jpablo.graphexplorer.viewer.desktop.DesktopDocumentRegistry
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The one-time migration from the old records to the metadata store, as the
  * desktop runs it through the commands of its shell (plan §4).
  *
  * Each test checks one step: the stop when `metadata/` exists, the files that
  * the plan creates, the entries that the commit writes, and what a failure
  * leaves. The old directory never changes.
  */
class DesktopMetadataMigrationSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "DesktopMetadataMigrationSpec"

  override def munitFixtures = List(mockStorageFixture())

  override def afterEach(context: AfterEach): Unit =
    Library.restoreDefault()
    DesktopDocumentRegistry.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private val now = 99L

  /** An old record, in the shape of the old writer (gx-core `LegacyRecordSpec`). */
  private def record(id: String, text: String, file: Option[String] = None, hidden: Set[String] = Set.empty): String =
    ujson.write(
      ujson.Obj(
        "id"      -> ujson.Obj("value" -> id),
        "name"    -> id,
        "folder"  -> ujson.Obj("segments" -> ujson.Arr()),
        "format"  -> "DOT",
        "text"    -> text,
        "binding" -> file.fold(ujson.Null)(f => ujson.Obj("origin" -> s"file://$f", "mode" -> "Sync", "baseHash" -> "00", "lastSyncAt" -> 1)),
        "metadata" -> (if hidden.isEmpty then ujson.Obj() else ujson.Obj("hiddenElements" -> ujson.Arr.from(hidden.toSeq))),
        "createdAt" -> 10,
        "updatedAt" -> 20
      )
    )

  /** A shell with no `metadata/`, and these old records. */
  private def withOldLibrary[A](records: (String, String)*)(f: FakeDesktopShell => Future[A]): Future[A] =
    val shell = FakeDesktopShell()
    shell.migrated = false
    records.foreach((name, json) => shell.legacy(name) = json)
    shell.install()
    f(shell).transform { result => shell.uninstall(); result }

  private def done(outcome: DesktopMigrationOutcome): Vector[String] = outcome match
    case DesktopMigrationOutcome.Done(report) => report
    case other                                => fail(s"expected Done, got $other")

  // ------------------------------------------------ step 1: the stop

  test("when metadata/ exists, the migration does nothing"):
    val shell = FakeDesktopShell()
    shell.legacy("n.json") = record("Notes", "digraph { n }")
    shell.install()
    DesktopMetadataMigration
      .run(() => now)
      .map: outcome =>
        same(outcome, DesktopMigrationOutcome.AlreadyDone)
        same(shell.commands, List("library_legacy_list"), "the migration ran a step after the stop")
      .transform { r => shell.uninstall(); r }

  // ------------------------------------------- the plan, then the commit

  test("a record with no file becomes a new file in the folder for new diagrams, and its entry is committed"):
    withOldLibrary("n.json" -> record("Notes", "digraph { n }", hidden = Set("node:n"))): shell =>
      DesktopMetadataMigration.run(() => now).map: outcome =>
        val path   = s"${shell.folder}/Notes.dot"
        val report = done(outcome)
        same(shell.files.get(path), Some("digraph { n }"), "the text of the record is not in a new file")
        check(shell.migrated, "the commit did not make metadata/")
        same(shell.entryOf(path).map(e => (e.metadata.hiddenElements, e.createdAt, e.updatedAt)), Some((Set("node:n"), 10L, now)))
        check(report.exists(_.contains(path)), s"the report does not name the new file: $report")

  test("a record bound to a file that exists gives its metadata to that file, and makes no new file"):
    withOldLibrary("a.json" -> record("arch", "digraph { a }", file = Some("/w/arch.dot"), hidden = Set("node:a"))): shell =>
      shell.files("/w/arch.dot") = "digraph { a }"
      DesktopMetadataMigration.run(() => now).map: outcome =>
        done(outcome)
        same(shell.callsOf("create_document").size, 0, "a file was created for a record whose file exists")
        same(shell.entryOf("/w/arch.dot").map(_.metadata.hiddenElements), Some(Set("node:a")))
        same(shell.files.get("/w/arch.dot"), Some("digraph { a }"), "the file of the person changed")

  // Finding 12. `bound` holds each file by the RAW path of its record, but the
  // plan asks `readFile` for the CANONICAL path that the shell gave. When the
  // two spellings differ (macOS `/tmp` and `/private/tmp`, Windows `/` and
  // `\`, a folder through a symlink), the read misses. The plan takes the file
  // for missing: it makes a copy in the folder for new diagrams, and the copy
  // gets the entry. The real file gets none.
  test("a record bound to a file with a different canonical spelling gives its metadata to that file"):
    withOldLibrary("a.json" -> record("arch", "digraph { a }", file = Some("/tmp/w/arch.dot"), hidden = Set("node:a"))): shell =>
      shell.canonical = p => if p.startsWith("/tmp/") then "/private" + p else p
      shell.files("/private/tmp/w/arch.dot") = "digraph { a }"
      DesktopMetadataMigration.run(() => now).map: outcome =>
        done(outcome)
        same(shell.callsOf("create_document").size, 0, s"the migration made a copy of a file that exists: ${shell.files.keys.toList.sorted}")
        same(
          shell.entryOf("/private/tmp/w/arch.dot").map(_.metadata.hiddenElements),
          Some(Set("node:a")),
          s"the file of the record has no entry: ${shell.entries.values.map(upickle.default.read[LibraryEntry](_).path).toList}"
        )

  test("a record whose text differs from its file: its text goes to Recovered/, and both are in the library"):
    withOldLibrary("a.json" -> record("arch", "digraph { record }", file = Some("/w/arch.dot"))): shell =>
      shell.files("/w/arch.dot") = "digraph { file }"
      DesktopMetadataMigration.run(() => now).map: outcome =>
        val recovered = s"${shell.folder}/Recovered/arch.dot"
        done(outcome)
        same(shell.files.get(recovered), Some("digraph { record }"))
        same(shell.files.get("/w/arch.dot"), Some("digraph { file }"), "the file of the person changed")
        check(shell.entryOf(recovered).isDefined && shell.entryOf("/w/arch.dot").isDefined, s"entries: ${shell.entries.keys}")

  test("a record bound to a missing file becomes a new file, and the report names the old path"):
    withOldLibrary("g.json" -> record("Gone", "digraph { g }", file = Some("/gone/g.dot"))): shell =>
      DesktopMetadataMigration.run(() => now).map: outcome =>
        val report = done(outcome)
        same(shell.files.get(s"${shell.folder}/Gone.dot"), Some("digraph { g }"))
        check(report.exists(_.contains("/gone/g.dot")), s"the report does not name the old path: $report")

  test("a file that an earlier run created, with the same text, is used again and not made again"):
    // A crash after the files and before the commit leaves the files. The
    // next start finds them, and makes no copies.
    withOldLibrary("n.json" -> record("Notes", "digraph { n }")): shell =>
      shell.files(s"${shell.folder}/Notes.dot") = "digraph { n }"
      DesktopMetadataMigration.run(() => now).map: outcome =>
        done(outcome)
        same(shell.callsOf("create_document").size, 0, "the migration made a copy of a file of an earlier run")
        check(shell.entryOf(s"${shell.folder}/Notes.dot").isDefined, "the file of the earlier run has no entry")

  test("the migration never writes over a file that exists"):
    withOldLibrary("n.json" -> record("Notes", "digraph { library }")): shell =>
      shell.files(s"${shell.folder}/Notes.dot") = "digraph { mine }"
      DesktopMetadataMigration.run(() => now).map: outcome =>
        done(outcome)
        same(shell.files.get(s"${shell.folder}/Notes.dot"), Some("digraph { mine }"))
        same(shell.files.get(s"${shell.folder}/Notes 2.dot"), Some("digraph { library }"))

  test("an old record that does not parse is reported, and the others move"):
    withOldLibrary("n.json" -> record("Notes", "digraph { n }"), "bad.json" -> "{ not json"): shell =>
      DesktopMetadataMigration.run(() => now).map: outcome =>
        val report = done(outcome)
        same(shell.entries.size, 1)
        check(report.exists(_.contains("bad.json")), s"the report does not name the bad record: $report")

  test("the old directory stays unchanged"):
    val records = Seq("n.json" -> record("Notes", "digraph { n }"), "a.json" -> record("arch", "digraph { a }", Some("/w/a.dot")))
    withOldLibrary(records*): shell =>
      shell.files("/w/a.dot") = "digraph { other }"
      DesktopMetadataMigration.run(() => now).map: outcome =>
        done(outcome)
        same(shell.legacy.toMap, records.toMap)

  // ------------------------------------------------------- a failure

  test("a file that cannot be created stops the migration before the commit, and the next start tries again"):
    withOldLibrary("n.json" -> record("Notes", "digraph { n }")): shell =>
      shell.refusals("create_document") = ("DOCUMENT_DENIED", "path is blocked by denylist")
      DesktopMetadataMigration
        .run(() => now)
        .flatMap: outcome =>
          outcome match
            case DesktopMigrationOutcome.Failed(reason, _) =>
              check(reason.contains("denylist"), s"the reason does not say why: $reason")
            case other => check(false, s"expected Failed, got $other")
          check(!shell.migrated, "metadata/ exists after a failure")
          same(shell.callsOf("library_commit_migration").size, 0)
          // The next start, with the cause gone.
          shell.refusals.clear()
          DesktopMetadataMigration.run(() => now)
        .map: outcome =>
          done(outcome)
          check(shell.migrated, "the second start did not finish the migration")

  // -------------------------------------------------- the start of the app

  test("the start runs the migration, loads the library, and shows the report one time"):
    withOldLibrary("n.json" -> record("Notes", "digraph { n }")): shell =>
      DesktopStartup
        .run(() => now)
        .flatMap: library =>
          same(library.indexNow().diagrams.map(_.name), List("Notes"), "the migrated diagram is not in the library")
          check(Library.notice.now().exists(_.exists(_.contains("Notes.dot"))), s"no report on the home page: ${Library.notice.now()}")
          Library.notice.set(None)
          library.close()
          DesktopStartup.run(() => now)
        .map: library =>
          library.close()
          same(Library.notice.now(), None, "the report shows a second time")
