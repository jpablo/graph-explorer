package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.viewer.models.{ElementId, GroupId}

/** The metadata tier: operations on the METADATA of a diagram, never on its
  * source (D7.2).
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
  *
  * The diagram file has no name and no folder in the library (ADR 0001), so
  * there is no command for them. A person uses tags and the folders of the
  * file system.
  */
enum MetadataCommand derives CanEqual:
  /** Hidden elements and collapsed groups. */
  case Hide(targets: Set[ElementId])
  case Unhide(targets: Set[ElementId])
  case UnhideAll
  case Collapse(groups: Set[GroupId])
  case Expand(groups: Set[GroupId])
  case ExpandAll

  /** Tags and notes. An empty note removes the note. */
  case Tag(tags: List[String])
  case Untag(tags: List[String])
  case SetNotes(notes: String)

object MetadataCommand:

  val tier: Tier = Tier.Metadata
