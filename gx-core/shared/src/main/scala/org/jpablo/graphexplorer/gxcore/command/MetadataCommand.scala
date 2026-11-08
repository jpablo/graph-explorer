package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.viewer.models.{ElementId, GroupId}

/** The metadata tier: operations on the stored METADATA of a diagram, never on
  * its source (D7.2).
  *
  * The split is the one `sources-and-library-architecture.md` §5.3.1 draws and
  * calls a design rule rather than bookkeeping: anything here survives a pull,
  * so folding a cluster or hiding a node never conflicts with a regenerating
  * origin — anything in the text does conflict. `hide` and `collapse` are the
  * clearest cases: neither changes a byte of DOT, which is why they are here and
  * not with `set-attribute`.
  *
  * This tier is headless for the same reason as the source tier. The metadata
  * is state on disk, so `gx` can read and write it with no window.
  */
enum MetadataCommand derives CanEqual:
  /** Hidden elements and collapsed groups. */
  case Hide(targets: Set[ElementId])
  case Unhide(targets: Set[ElementId])
  case UnhideAll
  case Collapse(groups: Set[GroupId])
  case Expand(groups: Set[GroupId])
  case ExpandAll

  /** Organisation. */
  case Tag(tags: List[String])
  case Untag(tags: List[String])
  case SetNotes(notes: String)
  case MoveToFolder(folder: String)
  case Rename(newName: String)

  /** Query. */
  case GetMetadata

  def name: String = MetadataCommand.nameOf(this)

  def isQuery: Boolean = this match
    case GetMetadata => true
    case _         => false

object MetadataCommand:

  def nameOf(command: MetadataCommand): String = command match
    case Hide(_)         => "hide"
    case Unhide(_)       => "unhide"
    case UnhideAll       => "unhide-all"
    case Collapse(_)     => "collapse"
    case Expand(_)       => "expand"
    case ExpandAll       => "expand-all"
    case Tag(_)          => "tag"
    case Untag(_)        => "untag"
    case SetNotes(_)     => "set-notes"
    case MoveToFolder(_) => "move-to-folder"
    case Rename(_)       => "rename-diagram"
    case GetMetadata       => "get-metadata"

  val names: Vector[String] = Vector(
    "hide",
    "unhide",
    "unhide-all",
    "collapse",
    "expand",
    "expand-all",
    "tag",
    "untag",
    "set-notes",
    "move-to-folder",
    "rename-diagram",
    "get-metadata"
  )

  val tier: Tier = Tier.Metadata
