package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.gxcore.model.{DiagramMetadata, OriginUri}
import upickle.default.ReadWriter

import scala.util.control.NonFatal

/** A folder of the OLD library, for example `/systems/core`.
  *
  * The library has no folders now (CONTEXT.md). Only an old record has one,
  * and the migration makes a tag of it (plan §4). Thus this type is private to
  * the migration.
  */
private[store] final case class FolderPath(segments: List[String]) derives CanEqual:
  def render: String  = if segments.isEmpty then "/" else segments.mkString("/", "/", "")
  def isRoot: Boolean = segments.isEmpty

private[store] object FolderPath:
  val root: FolderPath = FolderPath(Nil)

/** One old record of `$GX_HOME/library/diagrams/`, as the migration reads it.
  *
  * Before ADR 0001, each diagram in the library kept a copy of its source, and
  * a binding connected the copy to a file. The library now keeps only
  * metadata. Thus only the migration reads an old record, and nothing writes
  * one. This type is private to the migration for that reason.
  *
  * `origin` is the URI of the binding, as the record keeps it. It can be a
  * `file:` URI or a URI of a different scheme (`https:`).
  *
  * `name` and `folder` are the name and the folder of the old record. The
  * metadata of a diagram has neither now, so the record keeps them apart from
  * `metadata`. The migration reads them to make the file name and the tags.
  */
private[store] final case class LegacyRecord(
    id:        String,
    name:      String,
    folder:    FolderPath,
    format:    String,
    text:      String,
    origin:    Option[String],
    metadata:  DiagramMetadata,
    createdAt: Long,
    updatedAt: Long
) derives CanEqual

private[store] object LegacyRecord:

  /** Read the JSON of one old record.
    *
    * The shape is the one that the old writer gave (`DiagramCodecSpec` pins
    * it). `name` and `folder` are at the top level. upickle did not write a
    * field of `metadata` that had its default, so each of these fields has its
    * default here too. The reader ignores the fields that the migration does
    * not use: the sync mode and the hashes of the binding.
    */
  def read(json: String): Either[String, LegacyRecord] =
    try
      val s = upickle.default.read[Stored](json)
      val m = s.metadata
      Right(
        LegacyRecord(
          id = s.id.value,
          name = s.name,
          folder = FolderPath(s.folder.segments),
          format = s.format,
          text = s.text,
          origin = s.binding.map(_.origin),
          metadata = DiagramMetadata(
            hiddenElements = m.hiddenElements,
            collapsedGroups = m.collapsedGroups,
            tags = m.tags,
            notes = m.notes,
            autoDetectFormat = m.autoDetectFormat
          ),
          createdAt = s.createdAt,
          updatedAt = s.updatedAt
        )
      )
    catch case NonFatal(e) => Left(e.toString)

  private final case class StoredId(value: String) derives ReadWriter
  private final case class StoredFolder(segments: List[String]) derives ReadWriter
  private final case class StoredBinding(origin: String) derives ReadWriter

  private final case class StoredMetadata(
      hiddenElements:   Set[String] = Set.empty,
      collapsedGroups:  Set[String] = Set.empty,
      tags:             List[String] = Nil,
      notes:            String = "",
      autoDetectFormat: Option[Boolean] = None
  ) derives ReadWriter

  private final case class Stored(
      id:        StoredId,
      name:      String,
      folder:    StoredFolder,
      format:    String,
      text:      String,
      binding:   Option[StoredBinding],
      metadata:  StoredMetadata,
      createdAt: Long,
      updatedAt: Long
  ) derives ReadWriter

/** The old records that a migration starts from (plan §4).
  *
  * A host makes this value from the files that it reads. The records stay
  * private to the migration. A host can see only what it must read before the
  * plan: the files that the records name, and the names that a new file can
  * take.
  *
  * The order is the order of the old library: by folder, then by name, then by
  * id. Thus the JVM and the desktop make the same plan from the same records.
  */
final class OldRecords private[store] (
    private[store] val records: Vector[LegacyRecord],
    /** One line for each record that does not parse, for the report. */
    val unreadable: Vector[String]
):

  def size: Int = records.size

  def isEmpty: Boolean = records.isEmpty && unreadable.isEmpty

  /** The paths of the files that the records name, as their `file:` URIs give
    * them. A host reads these files before it makes the plan.
    */
  def filePaths: Vector[String] =
    records.flatMap(_.origin).flatMap(raw => OriginUri.parse(raw).toOption).flatMap(_.filePath).distinct

  /** The base names (no extension, no number) that a new file for a record can
    * start with. A host reads the files with these names in the folder for new
    * diagrams, because the plan uses again a file that has the same text.
    */
  def fileNameBases: Vector[String] =
    records.map(r => SafeFileName.of(r.name)).distinct

object OldRecords:

  val empty: OldRecords = OldRecords(Vector.empty, Vector.empty)

  /** Read the files of `diagrams/`.
    *
    * @param files the name of each file, and its text, or the reason that the
    *              host could not read it
    */
  def fromFiles(files: Vector[(String, Either[String, String])]): OldRecords =
    val read = files.map: (name, content) =>
      content.flatMap(LegacyRecord.read).left.map: why =>
        s"the old record diagrams/$name cannot be read ($why). It stays there, unchanged."
    OldRecords(
      read.flatMap(_.toOption).sortBy(r => (r.folder.render, r.name.toLowerCase, r.id)),
      read.flatMap(_.left.toOption)
    )
