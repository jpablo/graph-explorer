package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.gxcore.model.DiagramMetadata
import org.jpablo.graphexplorer.viewer.models.{ElementId, GroupId}

/** The metadata tier, interpreted over `DiagramMetadata`.
  *
  * Pure: it takes the metadata of a diagram and gives the new metadata. The
  * caller saves the result in the library. The source tier does the same: it
  * gives back a `ViewerGraph`, and the caller decides where it goes. Thus `gx`
  * and (later) the UI can run the same command, and the command does not know
  * which of them asks.
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

  def run(meta: DiagramMetadata, command: MetadataCommand): DiagramMetadata =
    command match

      case MetadataCommand.Hide(targets) =>
        meta.copy(hiddenElements = meta.hiddenElements ++ refs(targets))

      case MetadataCommand.Unhide(targets) =>
        meta.copy(hiddenElements = meta.hiddenElements -- refs(targets))

      case MetadataCommand.UnhideAll =>
        meta.copy(hiddenElements = Set.empty)

      case MetadataCommand.Collapse(groups) =>
        meta.copy(collapsedGroups = meta.collapsedGroups ++ groupRefs(groups))

      case MetadataCommand.Expand(groups) =>
        meta.copy(collapsedGroups = meta.collapsedGroups -- groupRefs(groups))

      case MetadataCommand.ExpandAll =>
        meta.copy(collapsedGroups = Set.empty)

      case MetadataCommand.Tag(tags) =>
        // Order preserved, duplicates dropped: tags are a LIST because the user
        // chose an order, and a Set would quietly reorder it on every write.
        val added = tags.distinct.filterNot(meta.tags.contains)
        meta.copy(tags = meta.tags ++ added)

      case MetadataCommand.Untag(tags) =>
        meta.copy(tags = meta.tags.filterNot(tags.contains))

      case MetadataCommand.SetNotes(notes) =>
        meta.copy(notes = notes)

  private def refs(ids: Set[ElementId]): Set[String]    = ids.map(ElementRef.render)
  private def groupRefs(ids: Set[GroupId]): Set[String] = ids.map(ElementRef.render)
