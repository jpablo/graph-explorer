package org.jpablo.graphexplorer.gxcore.model

import org.jpablo.graphexplorer.viewer.models.DiagramId
import upickle.default.ReadWriter

/** Where a diagram sits in the library's own hierarchy.
  *
  * Virtual, not the filesystem's. §7: mirroring the filesystem breaks the moment
  * origins include URLs and database connections, which have no shared tree to
  * mirror. `gx import <directory>` mirrors a directory's shape into folders at
  * import time; after that the two are independent.
  */
final case class FolderPath(segments: List[String]) derives ReadWriter, CanEqual:
  def render: String            = if segments.isEmpty then "/" else segments.mkString("/", "/", "")
  def isRoot: Boolean           = segments.isEmpty
  def parent: FolderPath        = FolderPath(segments.dropRight(1))
  def child(name: String)       = FolderPath(segments :+ name)
  def isUnder(other: FolderPath): Boolean = segments.startsWith(other.segments)

object FolderPath:
  val root: FolderPath = FolderPath(Nil)

  /** Parse `/a/b`. Empty segments collapse, so `//a//b/` and `/a/b` are one
    * folder — the same "two spellings, one thing" rule the origin URIs follow.
    */
  def parse(raw: String): FolderPath =
    FolderPath(raw.split('/').iterator.filter(_.nonEmpty).toList)

/** Everything about a diagram that is not its text.
  *
  * The metadata/text split is a design rule, not bookkeeping (§5.3.1). Anything
  * living here survives a pull, so folding a cluster or hiding a node never
  * conflicts with a regenerating origin; anything living in the text does
  * conflict. New features should be asked which side they fall on, because the
  * answer decides whether they break the follow-the-file flow.
  */
final case class DiagramMetadata(
    /** Every diagram has a name, so the name has no default. */
    name:            String,
    folder:          FolderPath = FolderPath.root,
    hiddenElements:  Set[String] = Set.empty,
    collapsedGroups: Set[String] = Set.empty,
    tags:            List[String] = Nil,
    notes:           String = "",
    /** This flag controls whether the viewer detects the language of the source
      * again after each edit.
      *
      * The flag is here and not in the text because of the question in §5.3.1:
      * the flag survives a pull. A pull from an origin that a tool generated
      * again must not change the language mode that the user selected. `None`
      * is the value in each diagram from before the mode existed. The viewer
      * reads it as "on".
      */
    autoDetectFormat: Option[Boolean] = None
) derives CanEqual

/** The relationship between a diagram and an origin. */
final case class Binding(
    origin:     OriginUri,
    mode:       SyncMode,
    /** What both sides last agreed on — the `base` of the three-hash comparison. */
    baseHash:   ContentHash,
    lastSyncAt: Long
) derives ReadWriter, CanEqual

/** A diagram in the library.
  *
  * The diagram holds `text`, and the origin holds it too (§3.2). This is
  * intentional, for three reasons. A detached diagram, or a diagram with no
  * origin, needs text without an origin. An origin can be absent or offline,
  * and the diagram must render at that time. The three-hash comparison needs a
  * local copy to compare with. Thus `text` is a cache with an explicit
  * reconciliation, similar to a git working tree and its remote.
  */
final case class Diagram(
    id:        DiagramId,
    format:    String, // DiagramFormat.toString; a string so an unknown format round-trips
    text:      String,
    binding:   Option[Binding],
    metadata:  DiagramMetadata,
    createdAt: Long,
    updatedAt: Long
) derives CanEqual:
  def isBound: Boolean = binding.isDefined

object Diagram:

  /** The shape of a diagram on the disk.
    *
    * The shape is an API: older builds of `gx` and of the desktop read the same
    * library. Thus `name` and `folder` stay at the top level, outside
    * `metadata`. upickle omits a field that is equal to its default, so each
    * default here must not change.
    */
  private final case class Stored(
      id:        DiagramId,
      name:      String,
      folder:    FolderPath,
      format:    String,
      text:      String,
      binding:   Option[Binding],
      metadata:  StoredMetadata,
      createdAt: Long,
      updatedAt: Long
  ) derives ReadWriter

  private final case class StoredMetadata(
      hiddenElements:   Set[String] = Set.empty,
      collapsedGroups:  Set[String] = Set.empty,
      tags:             List[String] = Nil,
      notes:            String = "",
      autoDetectFormat: Option[Boolean] = None
  ) derives ReadWriter

  given ReadWriter[Diagram] = upickle.default.readwriter[Stored].bimap[Diagram](toStored, fromStored)

  private def toStored(d: Diagram): Stored =
    val m = d.metadata
    Stored(
      id = d.id,
      name = m.name,
      folder = m.folder,
      format = d.format,
      text = d.text,
      binding = d.binding,
      metadata = StoredMetadata(m.hiddenElements, m.collapsedGroups, m.tags, m.notes, m.autoDetectFormat),
      createdAt = d.createdAt,
      updatedAt = d.updatedAt
    )

  private def fromStored(s: Stored): Diagram =
    val m = s.metadata
    Diagram(
      id = s.id,
      format = s.format,
      text = s.text,
      binding = s.binding,
      metadata = DiagramMetadata(
        name = s.name,
        folder = s.folder,
        hiddenElements = m.hiddenElements,
        collapsedGroups = m.collapsedGroups,
        tags = m.tags,
        notes = m.notes,
        autoDetectFormat = m.autoDetectFormat
      ),
      createdAt = s.createdAt,
      updatedAt = s.updatedAt
    )
