package org.jpablo.graphexplorer.gxcore.model

import upickle.default.ReadWriter

/** A diagram's primary key: stable, internal, never derived from anything that
  * can change.
  *
  * Deliberately not the origin URI. §3.1: diagrams created in the app have no
  * origin, one file may legitimately back several records with different
  * metadata, and files get renamed — none of which a primary key may do. The
  * origin is an indexed attribute instead, and URI-keying is used where it
  * actually pays: the watch registry, where it collapses a file reached by two
  * spellings into one poller.
  */
final case class DiagramId(value: String) derives ReadWriter, CanEqual

object DiagramId:
  /** Derive an id from stable external text. Used by migration so that
    * re-running it maps an old project onto the same record instead of a second
    * copy — idempotence by construction rather than by a lookup that could race.
    */
  def derivedFrom(namespace: String, key: String): DiagramId =
    DiagramId(s"$namespace-$key")

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
    hiddenElements:  Set[String] = Set.empty,
    collapsedGroups: Set[String] = Set.empty,
    tags:            List[String] = Nil,
    notes:           String = "",
    /** Whether the viewer re-reads the document's language on every edit.
      *
      * Here rather than in the text because of §5.3.1's question: it survives a
      * pull. Re-pulling a regenerated origin must not silently switch the
      * language mode the user chose. `None` is every record written before the
      * mode existed, and reads as off.
      */
    autoDetectFormat: Option[Boolean] = None
) derives ReadWriter, CanEqual

object DiagramMetadata:
  val empty: DiagramMetadata = DiagramMetadata()

/** The relationship between a diagram and an origin. */
final case class Binding(
    origin:     OriginUri,
    mode:       SyncMode,
    /** What both sides last agreed on — the `base` of the three-hash comparison. */
    baseHash:   ContentHash,
    lastSyncAt: Long
) derives ReadWriter, CanEqual

/** A library record.
  *
  * `text` is held here AND at the origin, deliberately (§3.2): detached and
  * origin-less diagrams need text with no origin, an origin can be missing or
  * offline while the diagram still has to render, and the three-hash comparison
  * needs a local copy to compare against. It is a cache with explicit
  * reconciliation, like a git working tree against a remote.
  */
final case class Diagram(
    id:        DiagramId,
    name:      String,
    folder:    FolderPath,
    format:    String, // DiagramFormat.toString; a string so an unknown format round-trips
    text:      String,
    binding:   Option[Binding],
    metadata:  DiagramMetadata,
    createdAt: Long,
    updatedAt: Long
) derives ReadWriter, CanEqual:
  def isBound: Boolean = binding.isDefined
