package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.gxcore.fs.LineEnding
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, SourceTitle}

/** A file that the migration creates. `path` is absolute. */
final case class PlannedFile(path: String, text: String) derives CanEqual

/** What the migration does: the files to create, the library entries to
  * write, and the lines that tell a person what happened.
  */
final case class MetadataMigrationPlan(
    files:   Vector[PlannedFile],
    entries: Vector[LibraryEntry],
    report:  Vector[String]
) derives CanEqual

/** The one-time migration from the old records to the metadata store (plan
  * §4, ADR 0001).
  *
  * An old record ([[LegacyRecord]]) kept a copy of the source. The new
  * library keeps only metadata, by the path of a file. Thus each old record
  * needs a file:
  *
  *   - A record bound to a file that exists gives its metadata to that file.
  *     If the text of the record differs, the text goes into a new file in
  *     `Recovered/`, and that file is in the library too.
  *   - A record with no file, or with a file that is missing, gives its text
  *     to a new file in the folder for new diagrams.
  *
  * The migration never loses text, and it never writes over a file that
  * exists.
  *
  * This object is pure. It reads files only through `readFile`. A host
  * performs the plan: `MetadataMigrationExecutor` on the JVM, and the desktop
  * app through its IPC commands (`DesktopMetadataMigration`). The
  * browser-to-desktop migration makes its records with
  * `LocalStorageMigration.read`, and it uses the same plan.
  */
object MetadataMigration:

  /** The subfolder for the text of a record that its file does not have. */
  val RecoveredFolder = "Recovered"

  /** The name that the app gives to a new diagram. It is not information that
    * a person typed, so it does not become a tag.
    */
  private val DefaultName = "Untitled"

  /** The path of a file in the folder for new diagrams. `relative` uses `/`.
    * The result uses the separator of the folder: `\\` for a Windows folder.
    * A host that reads the files of the folder before the plan uses this
    * function, so that it reads the paths that the plan asks for.
    */
  def pathIn(diagramsFolder: String, relative: String): String =
    val separator = if isWindowsPath(diagramsFolder) then '\\' else '/'
    diagramsFolder.stripSuffix(separator.toString) + separator + relative.replace('/', separator)

  /** @param old            the old records ([[OldRecords.fromFiles]] or
    *                       `LocalStorageMigration.read`)
    * @param readFile       the text of a file, by its canonical path. `None`
    *                       when the file is missing.
    * @param diagramsFolder the canonical path of the folder for new diagrams
    * @param existingNames  the files that exist in that folder, as paths
    *                       relative to it with `/`: `Notes.dot`,
    *                       `Recovered/Notes.dot`
    * @param now            the time of the migration. It is the `updatedAt`
    *                       of each entry.
    * @param localPath      the canonical path of a `file:` origin. A host can
    *                       canonicalize the path again on its own file system.
    * @return the plan. Its report ends with a line for each record that does
    *         not parse.
    */
  def plan(
      old:            OldRecords,
      readFile:       String => Option[String],
      diagramsFolder: String,
      existingNames:  Set[String],
      now:            Long,
      localPath:      OriginUri => Option[String] = _.filePath
  ): MetadataMigrationPlan =
    val p = planRecords(old.records, readFile, diagramsFolder, existingNames, now, localPath)
    p.copy(report = p.report ++ old.unreadable)

  private[store] def planRecords(
      records:        Vector[LegacyRecord],
      readFile:       String => Option[String],
      diagramsFolder: String,
      existingNames:  Set[String],
      now:            Long,
      localPath:      OriginUri => Option[String]
  ): MetadataMigrationPlan =

    val existing = existingNames.map(_.toLowerCase)

    def join(relative: String): String = pathIn(diagramsFolder, relative)

    def fileOf(record: LegacyRecord): Option[String] =
      record.origin.flatMap(raw => OriginUri.parse(raw).toOption).flatMap(localPath)

    /** Each record whose file exists, with the path and the text of the file. */
    val withFile: Vector[(Int, LegacyRecord, String, String)] =
      records.zipWithIndex.flatMap: (record, index) =>
        fileOf(record).flatMap(path => readFile(path).map(text => (index, record, path, text)))

    val boundPaths = withFile.map(_._3).distinct

    def entry(state: State, record: LegacyRecord, path: String, sourceText: String, line: String): State =
      val meta      = record.metadata
      val name      = meta.name.trim
      val title     = SourceTitle.of(formatOf(record), sourceText)
      val base      = LibraryEntry.nameOf(path)
      val folderTag = Option.unless(meta.folder.isRoot)(meta.folder.segments.mkString("/"))
      val nameTag   = Option.when(name.nonEmpty && name != DefaultName && !title.contains(name) && name != base)(name)
      val e = LibraryEntry(
        path = path,
        metadata = DiagramMetadata(
          name = base,
          folder = FolderPath.root,
          hiddenElements = meta.hiddenElements,
          collapsedGroups = meta.collapsedGroups,
          tags = (meta.tags ++ folderTag ++ nameTag).distinct,
          notes = meta.notes,
          autoDetectFormat = meta.autoDetectFormat
        ),
        createdAt = record.createdAt,
        updatedAt = now
      )
      val lines = Vector(line) ++
        folderTag.map(t => s"${label(record)}: the folder ${meta.folder.render} is now the tag \"$t\".") ++
        nameTag.map(t => s"${label(record)}: the name is now the tag \"$t\".")
      state.copy(entries = state.entries :+ e, claimed = state.claimed + path.toLowerCase, report = state.report ++ lines)

    /** Give the text of a record to a new file in the folder, or in a
      * subfolder of it. A file that exists is used only when it has the same
      * text and no entry has it yet. This makes a second run after a crash
      * safe: it finds the files of the first run again.
      */
    def newFile(state: State, record: LegacyRecord, subfolder: Option[String], why: String): State =
      val base = SafeFileName.of(record.metadata.name)
      val ext  = extensionOf(formatOf(record))
      val dir  = subfolder.fold("")(_ + "/")

      def reusable(relative: String): Boolean =
        val path = join(relative)
        !state.claimed.contains(path.toLowerCase) && readFile(path).exists(sameText(record.text, _))

      val relative = Iterator
        .from(1)
        .map(n => dir + SafeFileName.numbered(base, n) + ext)
        .find(r => !state.taken.contains(r.toLowerCase) && (!existing.contains(r.toLowerCase) || reusable(r)))
        .get // The sets are finite, so a free name exists.
      val path   = join(relative)
      val reused = existing.contains(relative.toLowerCase)
      val line =
        if reused then s"${label(record)}: $why. Its text is in $path, a file that has the same text."
        else s"${label(record)}: $why. Its text is now in $path."
      val withFile = state.copy(
        files = if reused then state.files else state.files :+ PlannedFile(path, record.text),
        taken = state.taken + relative.toLowerCase
      )
      entry(withFile, record, path, record.text, line)

    def recover(state: State, record: LegacyRecord, why: String): State =
      newFile(state, record, Some(RecoveredFolder), why)

    // The bound files go first. Thus a new file cannot take the place of a
    // file that a record has.
    val afterBound = boundPaths.foldLeft(State(claimed = boundPaths.map(_.toLowerCase).toSet)): (state, path) =>
      val group    = withFile.filter(_._3 == path)
      val fileText = group.head._4
      // One file has one set of metadata. The first choice is a record whose
      // text is the text of the file. The next choice is the record with the
      // latest change. Each other record gets a file in Recovered/.
      val ordered = group.map(_._2).sortBy(r => (!sameText(r.text, fileText), -r.updatedAt, r.id))
      val owner   = ordered.head
      val moved   = entry(state, owner, path, fileText, s"${label(owner)}: the metadata moves to $path.")
      val checked =
        if sameText(owner.text, fileText) then moved
        else recover(moved, owner, s"its text differs from $path")
      ordered.tail.foldLeft(checked): (s, other) =>
        recover(s, other, s"$path already has the metadata of ${label(owner)}")

    val boundIndexes = withFile.map(_._1).toSet
    val result = records.zipWithIndex.filterNot((_, i) => boundIndexes.contains(i)).foldLeft(afterBound):
      case (state, (record, _)) =>
        val why = record.origin match
          case None                                 => "it had no file"
          case Some(o) if OriginUri.parse(o).isLeft => s"its origin $o is not a file"
          case Some(o)                              => s"its file ${fileOf(record).getOrElse(o)} is missing"
        newFile(state, record, None, why)

    MetadataMigrationPlan(result.files, result.entries, result.report)

  /** What a fold over the records keeps. `taken` has the names (lower case,
    * relative to the folder) that this plan gives to files. `claimed` has the
    * paths (lower case) that have an entry. Lower case, because macOS and
    * Windows have file systems that ignore case.
    */
  private final case class State(
      files:   Vector[PlannedFile] = Vector.empty,
      entries: Vector[LibraryEntry] = Vector.empty,
      report:  Vector[String] = Vector.empty,
      taken:   Set[String] = Set.empty,
      claimed: Set[String] = Set.empty
  )

  private def label(record: LegacyRecord): String = s"\"${record.metadata.name}\" [${record.id}]"

  /** The old model kept the text of a bound record in the line endings of its
    * file. Thus a different line ending is not a different text.
    */
  private def sameText(recordText: String, fileText: String): Boolean =
    recordText == fileText || LineEnding.detect(fileText).applyTo(recordText) == fileText

  /** The format of a record. The record keeps it as text, so a format from a
    * later version is possible. Then the text decides.
    */
  private def formatOf(record: LegacyRecord): DiagramFormat =
    DiagramFormat.values.find(_.toString == record.format).getOrElse(DiagramFormat.detect(record.text))

  private def extensionOf(format: DiagramFormat): String = format match
    case DiagramFormat.DOT     => ".dot"
    case DiagramFormat.Mermaid => ".mmd"

  private def isWindowsPath(path: String): Boolean =
    "^[A-Za-z]:\\\\".r.findPrefixOf(path).isDefined || path.startsWith("\\\\")
