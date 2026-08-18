package org.jpablo.graphexplorer.projects

import org.jpablo.graphexplorer.gxcore.model.{Diagram, DiagramId}
import org.jpablo.graphexplorer.gxcore.store.{DiagramFileName, DiagramSink, LocalStorageMigration, MigrationReport}
import org.jpablo.graphexplorer.viewer.desktop.DesktopIpc
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.control.NonFatal

/** Bringing the browser library onto disk, once (D7.3).
  *
  * Non-destructive by choice: `localStorage` is left exactly as it is, so the
  * browser copy remains a fallback until there is reason to trust the disk one.
  * The cost is duplicated data for a while, which is the cheaper mistake.
  */
object DesktopMigration:

  /** Marks the copy as done.
    *
    * Needed because "already in the store" is NOT a safe test for whether to
    * migrate again. Leaving `localStorage` populated means a diagram deleted in
    * the desktop is still in the browser library, and a second run would
    * cheerfully resurrect it — deletion would silently not stick. Running once
    * is what makes the two libraries diverge legitimately from then on.
    */
  private val DoneKey = "graph-explorer.migrated-to-disk"

  def hasRun: Boolean = ProjectStorage.readFlag(DoneKey).isDefined

  /** @param existing ids already on disk, so a re-run cannot duplicate them */
  def runOnce(existing: Set[String]): Future[Option[MigrationReport]] =
    if hasRun then Future.successful(None)
    else
      val sink = CollectingSink(existing)
      LocalStorageMigration.migrate(
        directoryJson = ProjectStorage.rawDirectoryJson,
        payloadFor = ProjectStorage.rawPayload,
        store = sink,
        now = () => js.Date.now().toLong
      ) match
        case Left(why) =>
          // A directory we cannot parse is not a reason to start with an empty
          // library, and it is certainly not a reason to mark the copy done.
          dom.console.error(s"[library] the browser library could not be read, so nothing was copied: $why")
          Future.successful(None)

        case Right(report) =>
          writeAll(sink.collected.toVector).map: failures =>
            if failures.isEmpty then
              // Only now. Marking it done after a partial write would strand
              // whatever failed, with the flag saying there was nothing to do.
              ProjectStorage.writeFlag(DoneKey, js.Date.now().toLong.toString)
              Some(report)
            else
              dom.console.error(
                s"[library] ${failures.size} diagram(s) could not be copied to disk; " +
                  "the browser library is untouched and the copy will be retried next launch."
              )
              failures.foreach(f => dom.console.error(s"[library]   $f"))
              None

  private def writeAll(diagrams: Vector[Diagram]): Future[Vector[String]] =
    Future
      .sequence:
        diagrams.map: d =>
          DesktopIpc
            .invoke(
              "library_write",
              js.Dynamic.literal(
                name = s"${DiagramFileName.of(d.id)}.json",
                json = upickle.default.write(d, indent = 2)
              )
            )
            .map(_ => None)
            .recover { case NonFatal(e) => Some(s"${d.id.value}: ${e.getMessage}") }
      .map(_.flatten)

  /** Collects what the migration decided to write, so the actual disk writes
    * can be awaited and CHECKED.
    *
    * `DiagramSink.write` is synchronous and the desktop's is not. Firing the
    * invokes from inside it and returning `Right` regardless would produce a
    * report that says "imported" about writes nobody watched land.
    */
  private final class CollectingSink(existing: Set[String]) extends DiagramSink:
    val collected: mutable.Buffer[Diagram] = mutable.Buffer.empty

    def initialize(): Unit = ()

    def contains(id: DiagramId): Boolean =
      existing.contains(id.value) || collected.exists(_.id.value == id.value)

    def write(diagram: Diagram): Either[String, Diagram] =
      collected += diagram
      Right(diagram)
