package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.viewer.models.DiagramId

import scala.util.control.NonFatal

final case class MigrationReport(
    imported: Vector[DiagramId] = Vector.empty,
    /** Already in the store: the store has a diagram for this browser diagram,
      * and the migration did not change it. Thus the migration is safe when it
      * runs again.
      */
    skipped:  Vector[DiagramId] = Vector.empty,
    failed:   Vector[(String, String)] = Vector.empty
):
  def total: Int      = imported.size + skipped.size + failed.size
  def isEmpty: Boolean = total == 0

/** The migration moves the browser library to the disk (§9).
  *
  * The web app keeps its own library in `localStorage`. That library does not
  * sync, and the migration does not change it. The web app has no file system,
  * and that is the reason for the browser library. The desktop offers this
  * import one time, at the first start of v2.
  *
  * Two rules apply. Both come from bugs, not from assumptions:
  *
  *   1. **The migration never destroys data.** No code here deletes or
  *      overwrites. The migration runs on all of the library of a user. If it
  *      goes wrong, the result is not "a bad import" but "their work is gone".
  *      `BrowserLibrary` had bugs of this type before. The empty-index guard
  *      in `updateIndex` and `guardedDiagramName` exist because a blank value
  *      once got into the storage and overwrote real data.
  *   2. **The migration is idempotent because of its design.** Each id comes
  *      from the id of the browser diagram. Thus a second run finds the same
  *      diagrams in the store and skips them. A derived key gives the
  *      idempotence, not a "have I run yet?" flag. A flag can be lost, or the
  *      code can write it before the work is complete.
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
    * diagram, and its id cannot be equal to an id from `DiagramId.random`.
    */
  private val Namespace = "ls"

  def idFor(browserDiagramId: String): DiagramId = DiagramId.derivedFrom(Namespace, browserDiagramId)

  /** @param directoryJson the value of the `graph-explorer.projects` key
    * @param payloadFor    the id of a browser diagram -> its `PersistedDiagramState` JSON
    */
  def migrate(
      directoryJson: String,
      payloadFor:    String => Option[String],
      store:         DiagramSink,
      now:           () => Long = () => System.currentTimeMillis()
  ): Either[String, MigrationReport] =
    parseDirectory(directoryJson).map: browserDiagrams =>
      store.initialize()
      browserDiagrams.foldLeft(MigrationReport()): (report, browserDiagram) =>
        val id = idFor(browserDiagram.id)
        if store.contains(id) then report.copy(skipped = report.skipped :+ id)
        else
          payloadFor(browserDiagram.id) match
            case None =>
              // An entry of the library index that has no payload. v1 could
              // make these: a deleted browser diagram whose payload is gone but
              // whose entry stayed, or the opposite. The migration records a
              // failure. It does not make an empty diagram in its place.
              report.copy(failed = report.failed :+ (browserDiagram.id, "no stored payload"))
            case Some(payload) =>
              toDiagram(id, browserDiagram, payload, now()) match
                case Left(why) => report.copy(failed = report.failed :+ (browserDiagram.id, why))
                case Right(diagram) =>
                  store.write(diagram) match
                    case Left(err) => report.copy(failed = report.failed :+ (browserDiagram.id, err))
                    case Right(_)  => report.copy(imported = report.imported :+ id)

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

  private def toDiagram(
      id:             DiagramId,
      browserDiagram: LegacyProject,
      payload:        String,
      nowMs:          Long
  ): Either[String, Diagram] =
    try
      val o = ujson.read(payload).obj

      // `source` in the OLD schema is the diagram TEXT. The new model calls that
      // `text` and reserves "source"/"origin" for where it came from — which is
      // the opposite meaning, and the reason for renaming it (§2).
      val text = o.get("source").flatMap(_.strOpt).getOrElse("")

      val name = List(
        browserDiagram.name,
        o.get("projectName").flatMap(_.strOpt).getOrElse("")
      ).find(n => n.nonEmpty && n != "Untitled").getOrElse("Untitled")

      val format = o.get("format").flatMap(_.strOpt).filter(_.nonEmpty).getOrElse("DOT")

      Right(
        Diagram(
          id = id,
          format = format,
          text = text,
          // Migrated diagrams have no origin: they never came from a file, and
          // inventing one would bind them to a path that does not exist.
          binding = None,
          metadata = DiagramMetadata(
            name = name,
            folder = FolderPath.root,
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
