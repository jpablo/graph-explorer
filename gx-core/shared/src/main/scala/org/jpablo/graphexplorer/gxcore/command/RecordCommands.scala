package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.gxcore.model.{Diagram, FolderPath}
import org.jpablo.graphexplorer.viewer.models.{ElementId, GroupId}

/** What running a record command produced. */
enum RecordResult derives CanEqual:
  case Updated(diagram: Diagram)
  case Answered(value: ujson.Value)

  def updatedDiagram: Option[Diagram] = this match
    case Updated(d)  => Some(d)
    case Answered(_) => None

  def answer: Option[ujson.Value] = this match
    case Answered(v) => Some(v)
    case Updated(_)  => None

/** The record tier, interpreted over a `Diagram`.
  *
  * Pure: it takes a record and returns one. Persisting the result is the
  * caller's job, exactly as the document tier hands back a `ViewerGraph` and
  * lets the caller decide how it lands. That is what lets `gx` and (later) the
  * UI run the same command without the command knowing which of them is asking.
  *
  * ## How an element is spelled here
  *
  * `hiddenElements` and `collapsedGroups` are `Set[String]`, and the strings are
  * `ElementRef` spellings — `node:a`, `group:g1`. Not bare ids, and that is a
  * correction rather than a preference: bare `"n1"` cannot say whether a hidden
  * *node* or a hidden *group* was meant, so hiding a node would also hide a
  * group that happened to share its id. `collapsedGroups` only escaped the
  * problem because its field name implies the kind.
  */
object RecordCommands:

  def run(diagram: Diagram, command: RecordCommand): Either[CommandError, RecordResult] =
    val meta = diagram.metadata
    command match

      case RecordCommand.Hide(targets) =>
        Right(updated(diagram, meta.copy(hiddenElements = meta.hiddenElements ++ refs(targets))))

      case RecordCommand.Unhide(targets) =>
        Right(updated(diagram, meta.copy(hiddenElements = meta.hiddenElements -- refs(targets))))

      case RecordCommand.UnhideAll =>
        Right(updated(diagram, meta.copy(hiddenElements = Set.empty)))

      case RecordCommand.Collapse(groups) =>
        Right(updated(diagram, meta.copy(collapsedGroups = meta.collapsedGroups ++ groupRefs(groups))))

      case RecordCommand.Expand(groups) =>
        Right(updated(diagram, meta.copy(collapsedGroups = meta.collapsedGroups -- groupRefs(groups))))

      case RecordCommand.ExpandAll =>
        Right(updated(diagram, meta.copy(collapsedGroups = Set.empty)))

      case RecordCommand.Tag(tags) =>
        // Order preserved, duplicates dropped: tags are a LIST because the user
        // chose an order, and a Set would quietly reorder it on every write.
        val added = tags.filterNot(meta.tags.contains)
        Right(updated(diagram, meta.copy(tags = meta.tags ++ added)))

      case RecordCommand.Untag(tags) =>
        Right(updated(diagram, meta.copy(tags = meta.tags.filterNot(tags.contains))))

      case RecordCommand.SetNotes(notes) =>
        Right(updated(diagram, meta.copy(notes = notes)))

      case RecordCommand.MoveToFolder(folder) =>
        Right(RecordResult.Updated(diagram.copy(folder = FolderPath.parse(folder))))

      case RecordCommand.Rename(newName) =>
        if newName.trim.isEmpty then
          // An empty name makes a record unfindable in the UI's listing, and the
          // library's guards exist because that has happened before.
          Left(CommandError.BadArgument(command.name, "a diagram's name cannot be blank"))
        else Right(RecordResult.Updated(diagram.copy(name = newName)))

      case RecordCommand.GetRecord =>
        Right(
          RecordResult.Answered(
            ujson.Obj(
              "id"     -> diagram.id.value,
              "name"   -> diagram.name,
              "folder" -> diagram.folder.render,
              "format" -> diagram.format,
              "origin" -> diagram.binding.map(b => ujson.Str(b.origin.value)).getOrElse(ujson.Null),
              "mode"   -> diagram.binding.map(b => ujson.Str(b.mode.toString)).getOrElse(ujson.Null),
              "hidden" -> ujson.Arr.from(meta.hiddenElements.toVector.sorted.map(ujson.Str(_))),
              "collapsed" -> ujson.Arr.from(meta.collapsedGroups.toVector.sorted.map(ujson.Str(_))),
              "tags"  -> ujson.Arr.from(meta.tags.map(ujson.Str(_))),
              "notes" -> meta.notes
            )
          )
        )

  private def updated(diagram: Diagram, metadata: org.jpablo.graphexplorer.gxcore.model.DiagramMetadata) =
    RecordResult.Updated(diagram.copy(metadata = metadata))

  private def refs(ids: Set[ElementId]): Set[String]     = ids.map(ElementRef.render)
  private def groupRefs(ids: Set[GroupId]): Set[String]  = ids.map(ElementRef.render)
