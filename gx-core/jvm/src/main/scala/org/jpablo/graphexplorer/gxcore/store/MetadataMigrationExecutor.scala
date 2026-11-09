package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.gxcore.fs.{Documents, FileOrigins, GxHome, LineEnding}

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

enum MetadataMigrationResult derives CanEqual:
  /** `metadata/` exists, so the migration did nothing. */
  case AlreadyDone
  case Done(report: Vector[String])

  /** The migration stopped before the rename. `metadata/` does not exist, so
    * the next run tries again. The files that it created stay.
    */
  case Failed(reason: String, report: Vector[String])

/** The JVM half of the migration (plan §4): it reads the old records, makes
  * the plan with [[MetadataMigration.plan]], and performs it.
  *
  * The order makes a crash safe:
  *
  *   1. Create the files. A create never writes over a file that exists
  *      (`Documents.create`).
  *   2. Write the entries into a temporary directory beside `metadata/`.
  *   3. Rename that directory to `metadata/`. This step is atomic.
  *
  * Before step 3, `metadata/` does not exist, and the next run starts again.
  * The plan uses again a file that a crashed run created, because that file
  * has the same text. After step 3, each run does nothing.
  *
  * The old directory `diagrams/` stays unchanged. Thus an older desktop app
  * that is still installed shows its library as before.
  *
  * gx-cli calls this before each command that reads or writes the library
  * (`Cli.migrate`). A read or a change of a diagram file does not call it.
  */
object MetadataMigrationExecutor:

  /** @param gxHome         the root of `library/`, as `GxHome.resolve` gives it
    * @param diagramsFolder the folder for new diagrams, as `GxHome.diagramsDir`
    *                       gives it. It must be absolute.
    * @param beforePublish  a hook for tests. It gets the temporary directory
    *                       just before the rename.
    */
  def run(
      gxHome:         Path,
      diagramsFolder: Path,
      now:            () => Long = () => System.currentTimeMillis(),
      beforePublish:  Path => Unit = _ => ()
  ): MetadataMigrationResult =
    val metadataDir = GxHome.metadataDir(gxHome)
    if Files.exists(metadataDir) then MetadataMigrationResult.AlreadyDone
    else
      val old        = LibraryStore(GxHome.libraryDir(gxHome))
      val unreadable = old.unreadable().map:
        case StoreError.Unreadable(id, why) => s"the old record diagrams/${id.value}.json cannot be read ($why). It stays there, unchanged."
        case other                          => s"an old record cannot be read ($other). It stays in diagrams/, unchanged."
      val folder     = FileOrigins.canonicalize(diagramsFolder.toAbsolutePath, diagramsFolder.toAbsolutePath)

      val plan = MetadataMigration.plan(
        records = old.list(),
        readFile = path => Documents.read(Paths.get(path)).toOption.map(_.text),
        diagramsFolder = folder.toString,
        existingNames = namesIn(folder),
        now = now(),
        // The origin is canonical for the machine that made it. Canonicalize
        // it again here, so that the entry has the identity of the file on
        // this machine, and the separator of this platform.
        localPath = _.filePath.map: p =>
          val path = Paths.get(p)
          if path.isAbsolute then FileOrigins.canonicalize(path, folder).toString else p
      )

      execute(plan, metadataDir, beforePublish) match
        case MetadataMigrationResult.Done(report)           => MetadataMigrationResult.Done(report ++ unreadable)
        case MetadataMigrationResult.Failed(reason, report) => MetadataMigrationResult.Failed(reason, report ++ unreadable)
        case MetadataMigrationResult.AlreadyDone            => MetadataMigrationResult.AlreadyDone

  /** Perform a plan. The files first, then the entries in a temporary
    * directory, then the rename to `metadataDir`.
    */
  def execute(
      plan:          MetadataMigrationPlan,
      metadataDir:   Path,
      beforePublish: Path => Unit = _ => ()
  ): MetadataMigrationResult =
    val created = Vector.newBuilder[String]

    def failed(reason: String) =
      MetadataMigrationResult.Failed(reason, plan.report ++ created.result().map(p => s"the migration created $p."))

    val createError = plan.files.iterator
      .map: file =>
        // The text keeps its own line endings: `create` applies the one that
        // it gets.
        Documents.create(Paths.get(file.path), file.text, LineEnding.detect(file.text)) match
          case Right(_)  => created += file.path; None
          case Left(err) => Some(s"could not create ${file.path}: $err")
      .collectFirst { case Some(err) => err }

    createError match
      case Some(err) => failed(err)
      case None =>
        val parent = metadataDir.toAbsolutePath.getParent
        var temp   = Option.empty[Path]
        try
          Files.createDirectories(parent)
          val dir = Files.createTempDirectory(parent, s".${metadataDir.getFileName}-")
          temp = Some(dir)
          val store = MetadataStore(dir)
          plan.entries.iterator.map(store.put).collectFirst { case Left(err) => err } match
            case Some(err) =>
              deleteTree(dir)
              failed(s"could not write an entry: $err")
            case None =>
              beforePublish(dir)
              Files.move(dir, metadataDir, StandardCopyOption.ATOMIC_MOVE)
              MetadataMigrationResult.Done(plan.report)
        catch
          case NonFatal(e) =>
            // The temporary directory is the property of this run, so a
            // delete loses nothing. The files of the person stay.
            temp.foreach(deleteTree)
            failed(s"the migration stopped before the rename: $e")

  /** The files in the folder and in `Recovered/`, as paths relative to the
    * folder with `/`.
    */
  private def namesIn(folder: Path): Set[String] =
    def files(dir: Path, prefix: String): Set[String] =
      if !Files.isDirectory(dir) then Set.empty
      else
        val stream = Files.list(dir)
        try stream.iterator.asScala.map(p => prefix + p.getFileName.toString).toSet
        finally stream.close()
    files(folder, "") ++ files(folder.resolve(MetadataMigration.RecoveredFolder), s"${MetadataMigration.RecoveredFolder}/")

  private def deleteTree(root: Path): Unit =
    try
      if Files.exists(root) then
        val stream = Files.walk(root)
        try stream.iterator.asScala.toVector.reverse.foreach(p => Files.deleteIfExists(p))
        finally stream.close()
    catch case NonFatal(_) => ()
