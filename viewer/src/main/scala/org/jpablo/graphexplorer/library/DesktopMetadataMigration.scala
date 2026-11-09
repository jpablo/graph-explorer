package org.jpablo.graphexplorer.library

import org.jpablo.graphexplorer.gxcore.model.LibraryEntry
import org.jpablo.graphexplorer.gxcore.store.{MetadataMigration, MetadataMigrationPlan, OldRecords, PlannedFile}
import org.jpablo.graphexplorer.viewer.desktop.DesktopIpc
import org.jpablo.graphexplorer.viewer.desktop.DesktopIpc.DiagramsFolder

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.util.control.NonFatal

/** What a migration in the desktop did. */
enum DesktopMigrationOutcome derives CanEqual:
  /** The metadata store exists, so the migration did nothing. */
  case AlreadyDone

  case Done(report: Vector[String])

  /** The migration stopped. The metadata store does not exist, so the next
    * start tries again. The files that it created stay.
    */
  case Failed(reason: String, report: Vector[String])

/** The one-time migration from the old records to the metadata store, in the
  * desktop app (plan §4). `MetadataMigrationExecutor` does the same on the JVM.
  *
  * The desktop has no file system of its own, so each step is a command of the
  * shell:
  *
  *   1. `library_legacy_list`: the old records of `library/diagrams/`, and
  *      whether `metadata/` exists. When it exists, the migration stops here.
  *   2. `diagrams_folder`: the folder for new diagrams, and its files.
  *   3. `read_document`: the file of each bound record, and each file of the
  *      folder that the plan can use again.
  *   4. The plan: `MetadataMigration.plan`, the shared pure function.
  *   5. `create_document`: each planned file. The shell refuses a file that
  *      exists, so the migration never writes over a file.
  *   6. `library_commit_migration`: the entries, into a temporary directory
  *      that the shell renames to `metadata/` last.
  *
  * Before step 6, `metadata/` does not exist, and the next start begins
  * again. The plan then uses again each file that this run created, because
  * the file has the same text. The old directory `diagrams/` stays unchanged.
  */
object DesktopMetadataMigration:

  def run(now: () => Long = () => js.Date.now().toLong): Future[DesktopMigrationOutcome] =
    DesktopIpc
      .libraryLegacyList()
      .flatMap:
        case (true, _) => Future.successful(DesktopMigrationOutcome.AlreadyDone)
        case (false, records) =>
          val old = OldRecords.fromFiles(records.map(r => r.name -> r.content))
          migrate(old, now)
      .recover:
        // A step that the shell refused: for example a relative
        // `GX_DIAGRAMS_DIR`, or a folder that the access policy denies.
        case NonFatal(e) =>
          DesktopMigrationOutcome.Failed(s"the migration could not finish: ${DesktopLibrary.messageOf(e)}", Vector.empty)

  private def migrate(old: OldRecords, now: () => Long): Future[DesktopMigrationOutcome] =
    for
      folder  <- DesktopIpc.diagramsFolder()
      bound   <- MigrationSteps.readEach(old.filePaths)
      inPlace <- MigrationSteps.readEach(MigrationSteps.reusable(folder, old))
      plan = MetadataMigration.plan(
        old = old,
        readFile = path => (bound ++ inPlace).get(path).map(_.text),
        diagramsFolder = folder.path,
        existingNames = folder.existing.toSet,
        now = now(),
        // The shell gives the canonical path of each file that it read. The
        // entry gets that path, so that it has the identity of the file on
        // this machine.
        localPath = _.filePath.map(raw => bound.get(raw).fold(raw)(_.path))
      )
      outcome <- MigrationSteps.createFiles(plan).flatMap:
        case Left(failed) => Future.successful(failed)
        case Right(_) =>
          MigrationSteps
            .named(plan.entries)
            .flatMap(DesktopIpc.commitMigration)
            .map(_ => DesktopMigrationOutcome.Done(plan.report))
            .recover:
              case NonFatal(e) =>
                DesktopMigrationOutcome.Failed(
                  s"the entries could not be written: ${DesktopLibrary.messageOf(e)}",
                  plan.report ++ plan.files.map(f => s"the migration created ${f.path}.")
                )
    yield outcome

/** The steps that the two migrations of the desktop share. */
private[library] object MigrationSteps:

  /** The text of each path that the shell can read, by the path that was
    * asked for. A path that the shell cannot read is not in the result: the
    * plan reads it as a missing file.
    */
  def readEach(paths: Vector[String]): Future[Map[String, DesktopIpc.FileSnapshot]] =
    Future
      .traverse(paths.distinct): path =>
        DesktopIpc.readFile(path).map(s => Some(path -> s)).recover { case NonFatal(_) => None }
      .map(_.flatten.toMap)

  /** The files of the folder that the plan can use again: each name that
    * starts with the base name of a record. The plan uses again a file only
    * when it has the same text, so the plan needs the text of these files.
    */
  def reusable(folder: DiagramsFolder, old: OldRecords): Vector[String] =
    val bases = old.fileNameBases.map(_.toLowerCase)
    folder.existing
      .filter: name =>
        val lower = name.toLowerCase.stripPrefix(s"${MetadataMigration.RecoveredFolder.toLowerCase}/")
        bases.exists(lower.startsWith)
      .map(MetadataMigration.pathIn(folder.path, _))

  /** Create each planned file, one after the other, and stop at the first
    * failure. A failure is the outcome, with each file that the run created.
    */
  def createFiles(plan: MetadataMigrationPlan): Future[Either[DesktopMigrationOutcome.Failed, Unit]] =
    def loop(remaining: List[PlannedFile], created: Vector[String]): Future[Either[DesktopMigrationOutcome.Failed, Unit]] =
      remaining match
        case Nil => Future.successful(Right(()))
        case file :: rest =>
          DesktopIpc
            .createFile(file.path, file.text)
            .transformWith:
              case scala.util.Success(_) => loop(rest, created :+ file.path)
              case scala.util.Failure(e) =>
                Future.successful(
                  Left(
                    DesktopMigrationOutcome.Failed(
                      s"could not create ${file.path}: ${DesktopLibrary.messageOf(e)}",
                      plan.report ++ created.map(p => s"the migration created $p.")
                    )
                  )
                )
    loop(plan.files.toList, Vector.empty)

  /** Each entry with the name of its file in the store. */
  def named(entries: Vector[LibraryEntry]): Future[Vector[(String, String)]] =
    Future.traverse(entries): entry =>
      DesktopIpc.entryNameOf(entry.path).map(_ -> upickle.default.write(entry, indent = 2))
