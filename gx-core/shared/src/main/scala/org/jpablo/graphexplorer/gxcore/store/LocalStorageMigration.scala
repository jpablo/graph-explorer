package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.gxcore.model.*

import scala.util.control.NonFatal

final case class MigrationReport(
    imported: Vector[DiagramId] = Vector.empty,
    /** Already in the store: the record for this project exists, so it was left
      * exactly as it is. This is what makes re-running safe.
      */
    skipped:  Vector[DiagramId] = Vector.empty,
    failed:   Vector[(String, String)] = Vector.empty
):
  def total: Int      = imported.size + skipped.size + failed.size
  def isEmpty: Boolean = total == 0

/** Bringing the browser library onto disk (§9).
  *
  * The web app keeps its own `localStorage` library, unsynced and unchanged — it
  * has no filesystem, which is the whole reason it exists. This is the one-time
  * import the desktop offers on first v2 launch.
  *
  * Two rules, both learned rather than assumed:
  *
  *   1. **Never destructive.** Nothing here deletes or overwrites. Migration
  *      runs against a user's entire accumulated library, and the failure mode
  *      of getting it wrong is not "a bad import" but "their work is gone".
  *      `ProjectsStorage` already carries scar tissue from this class of bug —
  *      the `updateDirectory` empty-index guard and `guardedProjectName` both
  *      exist because a blank value once reached persistence and was written
  *      over real data.
  *   2. **Idempotent by construction.** Ids are derived from the project id, so
  *      a second run maps onto the same records and skips them. Idempotence via
  *      a derived key rather than a "have I run yet?" flag, because a flag can
  *      be lost or written before the work finishes.
  *
  * Parsing is deliberately lenient. The payloads were written by an older
  * version whose internal types have since moved on — `ElementIds` wraps a set
  * of a sealed trait with tagged JSON — and coupling a one-shot migration to
  * those shapes means it breaks precisely when it is finally needed. View state
  * is recovered on a best-effort basis: getting it wrong costs a fold, not a
  * document.
  */
object LocalStorageMigration:

  /** Namespace for derived ids, so a migrated record is recognisable as one and
    * cannot collide with an id minted natively.
    */
  private val Namespace = "ls"

  def idFor(projectId: String): DiagramId = DiagramId.derivedFrom(Namespace, projectId)

  /** @param directoryJson the `graph-explorer.projects` value
    * @param payloadFor    project id -> its `PersistedDiagramState` JSON
    */
  def migrate(
      directoryJson: String,
      payloadFor:    String => Option[String],
      store:         DiagramSink,
      now:           () => Long = () => System.currentTimeMillis()
  ): Either[String, MigrationReport] =
    parseDirectory(directoryJson).map: projects =>
      store.initialize()
      projects.foldLeft(MigrationReport()): (report, project) =>
        val id = idFor(project.id)
        if store.contains(id) then report.copy(skipped = report.skipped :+ id)
        else
          payloadFor(project.id) match
            case None =>
              // A directory entry with no payload. v1 could produce these: a
              // deleted project whose payload was removed but whose entry
              // lingered, or vice versa. Recording rather than inventing an
              // empty diagram to stand in for it.
              report.copy(failed = report.failed :+ (project.id, "no stored payload"))
            case Some(payload) =>
              toDiagram(id, project, payload, now()) match
                case Left(why) => report.copy(failed = report.failed :+ (project.id, why))
                case Right(diagram) =>
                  store.write(diagram) match
                    case Left(err) => report.copy(failed = report.failed :+ (project.id, err))
                    case Right(_)  => report.copy(imported = report.imported :+ id)

  private final case class LegacyProject(id: String, name: String, lastModified: Long, createdAt: Long)

  private def parseDirectory(json: String): Either[String, Vector[LegacyProject]] =
    try
      val projects = ujson.read(json).obj.get("projects").map(_.arr).getOrElse(ujson.Arr().arr)
      Right:
        projects.toVector.flatMap: entry =>
          val o  = entry.obj
          // ProjectId is a case class, so it serialises as {"value": "..."};
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
    catch case NonFatal(e) => Left(s"could not parse the projects directory: $e")

  private def toDiagram(
      id:      DiagramId,
      project: LegacyProject,
      payload: String,
      nowMs:   Long
  ): Either[String, Diagram] =
    try
      val o = ujson.read(payload).obj

      // `source` in the OLD schema is the diagram TEXT. The new model calls that
      // `text` and reserves "source"/"origin" for where it came from — which is
      // the opposite meaning, and the reason for renaming it (§2).
      val text = o.get("source").flatMap(_.strOpt).getOrElse("")

      val name = List(
        project.name,
        o.get("projectName").flatMap(_.strOpt).getOrElse("")
      ).find(n => n.nonEmpty && n != "Untitled").getOrElse("Untitled")

      val format = o.get("format").flatMap(_.strOpt).filter(_.nonEmpty).getOrElse("DOT")

      Right(
        Diagram(
          id = id,
          name = name,
          folder = FolderPath.root,
          format = format,
          text = text,
          // Migrated diagrams have no origin: they never came from a file, and
          // inventing one would bind them to a path that does not exist.
          binding = None,
          metadata = DiagramMetadata(
            hiddenElements = idsIn(o.get("hiddenElements")),
            // Groups by construction, so the kind is known even when the old
            // payload carried no tag.
            collapsedGroups = idsIn(o.get("collapsedGroups"), assume = Some("group"))
          ),
          createdAt = if project.createdAt > 0 then project.createdAt else nowMs,
          updatedAt = if project.lastModified > 0 then project.lastModified else nowMs
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
