package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.DiagramId

import scala.util.control.NonFatal

/** The migration moves the browser library to the disk (§9, plan §4).
  *
  * The web app keeps its own library in `localStorage`. That library does not
  * sync, and the migration does not change it. The web app has no file system,
  * and that is the reason for the browser library. The desktop offers this
  * copy one time, at its first start.
  *
  * A browser diagram becomes a file in the folder for new diagrams, and an
  * entry in the library, in the same way as an old record with no file
  * ([[MetadataMigration]]). Thus this object only reads: it changes each
  * browser diagram into an old record with no origin, and the shared plan
  * decides the file names and the entries.
  *
  * Two rules apply. Both come from bugs, not from assumptions:
  *
  *   1. **The migration never destroys data.** No code here deletes or
  *      overwrites, and the plan never writes over a file that exists. The
  *      migration runs on all of the library of a user. If it goes wrong, the
  *      result is not "a bad import" but "their work is gone".
  *   2. **A second run makes no copies.** The plan uses again a file that has
  *      the same text and no entry. The desktop also marks the copy as done
  *      (`DesktopMigration`), and it writes no entry over an entry that exists.
  *
  * The parse is lenient on purpose. An older version wrote the payloads, and
  * its internal types changed after that. For example, `ElementIds` wraps a set
  * of a sealed trait with tagged JSON. A one-time migration that depends on
  * those shapes breaks at the time when the user needs it. The migration
  * recovers the view state if it can. If it fails, the user loses a fold, not
  * the source.
  */
object LocalStorageMigration:

  /** The namespace for derived ids. With it, you can identify a migrated
    * diagram in the report, and its id cannot be equal to an id from
    * `DiagramId.random`.
    */
  private val Namespace = "ls"

  def idFor(browserDiagramId: String): DiagramId = DiagramId.derivedFrom(Namespace, browserDiagramId)

  /** Read the browser library as old records with no origin.
    *
    * @param directoryJson the value of the `graph-explorer.projects` key
    * @param payloadFor    the id of a browser diagram -> its payload JSON, which
    *                      is the stored form of `DiagramSnapshot` in the viewer
    * @return the records, in the order of the browser library, and a line for
    *         each browser diagram that the migration cannot read. `Left` when
    *         the library index does not parse: then nothing is copied.
    */
  def read(
      directoryJson: String,
      payloadFor:    String => Option[String],
      now:           () => Long = () => System.currentTimeMillis()
  ): Either[String, OldRecords] =
    parseDirectory(directoryJson).map: browserDiagrams =>
      val read = browserDiagrams.map: browserDiagram =>
        val label = s"the browser diagram \"${browserDiagram.name}\" [${browserDiagram.id}]"
        payloadFor(browserDiagram.id) match
          case None =>
            // An entry of the library index that has no payload. v1 could
            // make these: a deleted browser diagram whose payload is gone but
            // whose entry stayed, or the opposite. The migration reports it.
            // It does not make an empty diagram in its place.
            Left(s"$label has no stored payload. Nothing was copied for it.")
          case Some(payload) =>
            toRecord(idFor(browserDiagram.id), browserDiagram, payload, now()).left.map: why =>
              s"$label cannot be read ($why). It stays in the browser library, unchanged."
      OldRecords(read.flatMap(_.toOption), read.flatMap(_.left.toOption))

  private final case class LegacyProject(id: String, name: String, lastModified: Long, createdAt: Long)

  private def parseDirectory(json: String): Either[String, Vector[LegacyProject]] =
    try
      val browserDiagrams = ujson.read(json).obj.get("projects").map(_.arr).getOrElse(ujson.Arr().arr)
      Right:
        browserDiagrams.toVector.flatMap: entry =>
          val o  = entry.obj
          // DiagramId is a case class, so it serialises as {"value": "..."};
          // accept a bare string too, in case it ever did not.
          val id = o.get("id").flatMap:
            case ujson.Str(s) => Some(s)
            case ujson.Obj(f) => f.get("value").flatMap(_.strOpt)
            case _            => None
          id.map: pid =>
            LegacyProject(
              id = pid,
              name = o.get("name").flatMap(_.strOpt).getOrElse(""),
              lastModified = o.get("lastModified").flatMap(_.numOpt).map(_.toLong).getOrElse(0L),
              createdAt = o.get("createdAt").flatMap(_.numOpt).map(_.toLong).getOrElse(0L)
            )
    catch case NonFatal(e) => Left(s"could not parse the library index: $e")

  private def toRecord(
      id:             DiagramId,
      browserDiagram: LegacyProject,
      payload:        String,
      nowMs:          Long
  ): Either[String, LegacyRecord] =
    try
      val o = ujson.read(payload).obj

      // `source` in the OLD schema is the diagram TEXT. The new model calls that
      // `text` and reserves "source"/"origin" for where it came from — which is
      // the opposite meaning, and the reason for renaming it (§2).
      val text = o.get("source").flatMap(_.strOpt).getOrElse("")

      val name = List(
        browserDiagram.name,
        o.get("projectName").flatMap(_.strOpt).getOrElse("")
      ).find(n => n.nonEmpty && n != MetadataMigration.DefaultName).getOrElse(MetadataMigration.DefaultName)

      val format = o.get("format").flatMap(_.strOpt).filter(_.nonEmpty).getOrElse("DOT")

      Right(
        LegacyRecord(
          id = id.value,
          name = name,
          folder = FolderPath.root,
          format = format,
          text = text,
          // A browser diagram has no origin: it never came from a file, and
          // an invented one would name a path that does not exist.
          origin = None,
          metadata = DiagramMetadata(
            hiddenElements = idsIn(o.get("hiddenElements")),
            // Groups by construction, so the kind is known even when the old
            // payload carried no tag.
            collapsedGroups = idsIn(o.get("collapsedGroups"), assume = Some("group"))
          ),
          createdAt = if browserDiagram.createdAt > 0 then browserDiagram.createdAt else nowMs,
          updatedAt = if browserDiagram.lastModified > 0 then browserDiagram.lastModified else nowMs
        )
      )
    catch case NonFatal(e) => Left(s"could not parse the stored payload: $e")

  /** Pull element ids out of whatever shape the old version wrote, KEEPING the
    * kind.
    *
    * Recursive and shape-agnostic on purpose: the payloads span several versions
    * of `ElementIds`, and a strict decoder would reject the older ones outright.
    *
    * What changed: `$type` used to be skipped outright, so
    * `{"$type":"NodeId","value":"n1"}` became the bare string `n1` — and a bare
    * id cannot say whether a hidden NODE or a hidden GROUP was meant. Two
    * different elements sharing an id became one entry, and hiding either hid
    * both. The tag is the only place that information exists, so it is now read
    * rather than discarded, and the result is the `ElementRef` spelling the
    * command tier uses everywhere else (`node:n1`).
    *
    * A payload with no tag at all still yields the bare string. That shape was
    * never a real id — the lenient path exists so a corrupt entry costs the view
    * state and not the diagram.
    */
  private def idsIn(value: Option[ujson.Value], assume: Option[String] = None): Set[String] =
    def kindOf(tag: String): Option[String] = tag match
      case "NodeId"  => Some("node")
      case "ArrowId" => Some("arrow")
      case "GroupId" => Some("group")
      case _         => None

    def walk(v: ujson.Value, kind: Option[String]): Set[String] = v match
      case ujson.Str(s)     => Set(kind.fold(s)(k => s"$k:$s"))
      case ujson.Arr(items) => items.iterator.flatMap(walk(_, kind)).toSet
      case ujson.Obj(fields) =>
        val tagged = fields.get("$type").flatMap(_.strOpt).flatMap(kindOf).orElse(kind)
        fields.iterator
          .filterNot((k, _) => k.startsWith("$"))
          .flatMap((_, v) => walk(v, tagged))
          .toSet
      case _ => Set.empty
    value.fold(Set.empty)(walk(_, assume))
