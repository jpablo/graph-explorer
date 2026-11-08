package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.gxcore.model.{Diagram, DiagramMetadata, FolderPath}
import org.jpablo.graphexplorer.viewer.models.{ElementId, GroupId}

/** The result of a metadata command. */
enum MetadataResult derives CanEqual:
  case Updated(diagram: Diagram)
  case Answered(value: ujson.Value)

  def updatedDiagram: Option[Diagram] = this match
    case Updated(d)  => Some(d)
    case Answered(_) => None

  def answer: Option[ujson.Value] = this match
    case Answered(v) => Some(v)
    case Updated(_)  => None

/** The metadata tier, interpreted over a `Diagram`.
  *
  * Pure: it takes a diagram and returns a diagram. The caller saves the result.
  * The source tier does the same: it gives back a `ViewerGraph`, and the caller
  * decides where it goes. Thus `gx` and (later) the UI can run the same command,
  * and the command does not know which of them asks.
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
object MetadataCommands:

  def run(diagram: Diagram, command: MetadataCommand): Either[CommandError, MetadataResult] =
    val meta = diagram.metadata
    command match

      case MetadataCommand.Hide(targets) =>
        Right(updated(diagram, meta.copy(hiddenElements = meta.hiddenElements ++ refs(targets))))

      case MetadataCommand.Unhide(targets) =>
        Right(updated(diagram, meta.copy(hiddenElements = meta.hiddenElements -- refs(targets))))

      case MetadataCommand.UnhideAll =>
        Right(updated(diagram, meta.copy(hiddenElements = Set.empty)))

      case MetadataCommand.Collapse(groups) =>
        Right(updated(diagram, meta.copy(collapsedGroups = meta.collapsedGroups ++ groupRefs(groups))))

      case MetadataCommand.Expand(groups) =>
        Right(updated(diagram, meta.copy(collapsedGroups = meta.collapsedGroups -- groupRefs(groups))))

      case MetadataCommand.ExpandAll =>
        Right(updated(diagram, meta.copy(collapsedGroups = Set.empty)))

      case MetadataCommand.Tag(tags) =>
        // Order preserved, duplicates dropped: tags are a LIST because the user
        // chose an order, and a Set would quietly reorder it on every write.
        val added = tags.filterNot(meta.tags.contains)
        Right(updated(diagram, meta.copy(tags = meta.tags ++ added)))

      case MetadataCommand.Untag(tags) =>
        Right(updated(diagram, meta.copy(tags = meta.tags.filterNot(tags.contains))))

      case MetadataCommand.SetNotes(notes) =>
        Right(updated(diagram, meta.copy(notes = notes)))

      case MetadataCommand.MoveToFolder(folder) =>
        Right(updated(diagram, meta.copy(folder = FolderPath.parse(folder))))

      case MetadataCommand.Rename(newName) =>
        if newName.trim.isEmpty then
          // With an empty name, the user cannot find the diagram in the list of
          // the UI. The guards of the library exist because that occurred before.
          Left(CommandError.BadArgument(command.name, "a diagram's name cannot be blank"))
        else Right(updated(diagram, meta.copy(name = newName)))

      case MetadataCommand.GetMetadata =>
        // The metadata and nothing else. The id, the format, and the binding
        // are not metadata: `gx ls --json` prints them.
        Right(
          MetadataResult.Answered(
            ujson.Obj(
              "name"      -> meta.name,
              "folder"    -> meta.folder.render,
              "tags"      -> ujson.Arr.from(meta.tags.map(ujson.Str(_))),
              "notes"     -> meta.notes,
              "hidden"    -> ujson.Arr.from(meta.hiddenElements.toVector.sorted.map(ujson.Str(_))),
              "collapsed" -> ujson.Arr.from(meta.collapsedGroups.toVector.sorted.map(ujson.Str(_))),
              "autoDetectFormat" -> meta.autoDetectFormat.map(ujson.Bool(_)).getOrElse(ujson.Null)
            )
          )
        )

  private def updated(diagram: Diagram, metadata: DiagramMetadata) =
    MetadataResult.Updated(diagram.copy(metadata = metadata))

  private def refs(ids: Set[ElementId]): Set[String]     = ids.map(ElementRef.render)
  private def groupRefs(ids: Set[GroupId]): Set[String]  = ids.map(ElementRef.render)
