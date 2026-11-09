package org.jpablo.graphexplorer.library

import org.jpablo.graphexplorer.gxcore.store.{LocalStorageMigration, MetadataMigration}
import org.jpablo.graphexplorer.viewer.desktop.DesktopIpc
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.control.NonFatal

/** The browser library goes onto the disk, once (D7.3, plan §4).
  *
  * Each browser diagram becomes a NEW file in the folder for new diagrams,
  * and an entry in the library, in the same way as an old record with no file
  * (`MetadataMigration`). There is no binding: the file is the diagram.
  *
  * Non-destructive by choice: `localStorage` is left exactly as it is, so the
  * browser copy remains a fallback until there is reason to trust the disk one.
  * The cost is duplicated data for a while, which is the cheaper mistake.
  */
object DesktopMigration:

  /** Marks the copy as done.
    *
    * Needed because "already in the library" is NOT a safe test for whether
    * to copy again. `localStorage` stays as it is, so a diagram that a person
    * removed from the desktop library is still in the browser library, and a
    * second run would bring it back. Running once is what makes the two
    * libraries diverge legitimately from then on.
    */
  private val DoneKey = "graph-explorer.migrated-to-disk"

  def hasRun: Boolean = BrowserLibrary.readFlag(DoneKey).isDefined

  /** Copy the browser library into `library`, one time.
    *
    * The metadata store must exist: run [[DesktopMetadataMigration]] first.
    * An entry that exists is not written again, so a second run after a crash
    * changes no metadata that a person changed since.
    *
    * @return the lines of the report, or `None` when nothing was copied
    */
  def runOnce(library: DesktopLibrary, now: () => Long = () => js.Date.now().toLong): Future[Option[Vector[String]]] =
    if hasRun then Future.successful(None)
    else
      LocalStorageMigration.read(BrowserLibrary.rawIndexJson, BrowserLibrary.rawPayload, now) match
        case Left(why) =>
          // An index that does not parse is not a reason to start with an
          // empty library, and it is certainly not a reason to mark the copy
          // done.
          dom.console.error(s"[library] the browser library could not be read, so nothing was copied: $why")
          Future.successful(None)

        case Right(old) if old.isEmpty =>
          markDone()
          Future.successful(None)

        case Right(old) =>
          val copied =
            for
              folder  <- DesktopIpc.diagramsFolder()
              inPlace <- MigrationSteps.readEach(MigrationSteps.reusable(folder, old))
              plan = MetadataMigration.plan(
                old = old,
                readFile = path => inPlace.get(path).map(_.text),
                diagramsFolder = folder.path,
                existingNames = folder.existing.toSet,
                now = now()
              )
              created <- MigrationSteps.createFiles(plan)
              outcome <- created match
                case Left(failed) => Future.successful(Left(failed.reason +: failed.report))
                case Right(_) =>
                  Future
                    .traverse(plan.entries)(entry => library.keyOf(entry.path).map(_ -> entry))
                    .flatMap: keyed =>
                      // An entry that exists stays as it is: a second run
                      // after a crash finds the entries of the first run.
                      Future.traverse(keyed.filterNot((key, _) => library.contains(key)))((key, entry) => library.put(key, entry))
                    .map(_ => Right(plan.report))
            yield outcome

          copied
            .recover { case NonFatal(e) => Left(Vector(s"the browser library could not be copied: ${DesktopLibrary.messageOf(e)}")) }
            .map:
              case Right(report) =>
                // Only now. Marking it done after a partial write would strand
                // whatever failed, with the flag saying there was nothing to do.
                markDone()
                Some("The diagrams of the browser library are now files:" +: report)
              case Left(lines) =>
                dom.console.error(
                  "[library] the browser library could not be copied to disk; " +
                    "the browser library is untouched and the copy will be retried next launch."
                )
                lines.foreach(l => dom.console.error(s"[library]   $l"))
                Some("The browser library could not be copied. The next start tries again:" +: lines)

  private def markDone(): Unit = BrowserLibrary.writeFlag(DoneKey, js.Date.now().toLong.toString)
