package org.jpablo.graphexplorer.library

import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The start of the desktop library, in its order (plan §4, §5 phase 3a).
  *
  *   1. The migration of the old records, when `metadata/` does not exist.
  *   2. The load of the library: the entries, and the file of each entry.
  *   3. The copy of the browser library, one time. It needs the metadata
  *      store, so it does not run when step 1 failed.
  *
  * The report of each migration goes to the home page, one time
  * ([[Library.notice]]). A migration runs only one time, so its report shows
  * only one time.
  */
object DesktopStartup:

  def run(now: () => Long = () => js.Date.now().toLong): Future[DesktopLibrary] =
    for
      migrated <- DesktopMetadataMigration.run(now)
      library  <- DesktopLibrary.load()
      copied <- migrated match
        case DesktopMigrationOutcome.Failed(_, _) => Future.successful(None)
        case _                                    => DesktopMigration.runOnce(library, now)
    yield
      Library.showNotice(reportOf(migrated) ++ copied.getOrElse(Vector.empty))
      library

  private def reportOf(outcome: DesktopMigrationOutcome): Vector[String] = outcome match
    case DesktopMigrationOutcome.AlreadyDone => Vector.empty
    case DesktopMigrationOutcome.Done(report) =>
      if report.isEmpty then Vector.empty
      else "The library now keeps only metadata, and each diagram is a file:" +: report
    case DesktopMigrationOutcome.Failed(reason, report) =>
      dom.console.error(s"[library] the migration stopped: $reason")
      (s"The migration of the library stopped: $reason. The next start tries again." +: report)
