package org.jpablo.graphexplorer.gxcore.model

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
