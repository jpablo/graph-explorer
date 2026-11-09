package org.jpablo.graphexplorer.gxcore.model

import upickle.default.ReadWriter

/** The metadata of one diagram file, in the library (ADR 0001).
  *
  * The file is the only copy of the source. Thus an entry holds no text and no
  * binding. It holds only the canonical path of the file and what Graph
  * Explorer keeps about the file. The library is the set of these entries.
  *
  * `path` is the canonical path of the file, as `FileOrigins.canonicalize`
  * gives it. The store uses it as the key of the entry.
  */
final case class LibraryEntry(
    path:      String,
    metadata:  DiagramMetadata,
    createdAt: Long,
    updatedAt: Long
) derives CanEqual

object LibraryEntry:

  /** The name of a diagram that has no title: the base name of its file,
    * without the last extension. A name that starts with its only dot (for
    * example `.hidden`) keeps the dot.
    */
  def nameOf(path: String): String =
    val base = path.substring(path.lastIndexWhere(c => c == '/' || c == '\\') + 1)
    base.lastIndexOf('.') match
      case i if i > 0 => base.substring(0, i)
      case _          => base

  /** The text whose SHA-256 is the key of an entry: the canonical `file:` URI
    * of the path.
    *
    * The URI and not the path, because the URI has one spelling for each
    * file. On Windows, `C:\x\a.dot` and `C:/x/a.dot` are the same file, and
    * they give the same URI.
    *
    * The JVM hashes the UTF-8 bytes of this text with `Hashing.ofBytes`. The
    * desktop app can do the same in phase 3: its shell gives the canonical
    * path (V-13, `local-protocol/fixtures/canonicalization.json`), this shared
    * function gives the URI, and the shell command `hash_text` gives the
    * SHA-256 (`content-hashes.json`).
    */
  def keySource(canonicalPath: String): String =
    OriginUri.fromCanonicalPath(canonicalPath).value

  /** The shape of an entry on the disk.
    *
    * The shape is an API, because the desktop app reads the same files. The
    * stored form has no defaults, so that upickle writes each key each time.
    * The sets are sorted vectors, so that one entry always gives the same
    * bytes.
    *
    * The stored form has no `name` and no `folder`. Phase 3 removes the two
    * fields from `DiagramMetadata`. Until then, a read sets `name` to the base
    * name of the file and `folder` to the root.
    */
  private final case class Stored(
      path:             String,
      tags:             List[String],
      notes:            String,
      hiddenElements:   Vector[String],
      collapsedGroups:  Vector[String],
      autoDetectFormat: Option[Boolean],
      createdAt:        Long,
      updatedAt:        Long
  ) derives ReadWriter

  given ReadWriter[LibraryEntry] = upickle.default.readwriter[Stored].bimap[LibraryEntry](toStored, fromStored)

  private def toStored(e: LibraryEntry): Stored =
    val m = e.metadata
    Stored(
      path = e.path,
      tags = m.tags,
      notes = m.notes,
      hiddenElements = m.hiddenElements.toVector.sorted,
      collapsedGroups = m.collapsedGroups.toVector.sorted,
      autoDetectFormat = m.autoDetectFormat,
      createdAt = e.createdAt,
      updatedAt = e.updatedAt
    )

  private def fromStored(s: Stored): LibraryEntry =
    LibraryEntry(
      path = s.path,
      metadata = DiagramMetadata(
        // Phase 3 removes `name` and `folder` from `DiagramMetadata`.
        name = nameOf(s.path),
        folder = FolderPath.root,
        hiddenElements = s.hiddenElements.toSet,
        collapsedGroups = s.collapsedGroups.toSet,
        tags = s.tags,
        notes = s.notes,
        autoDetectFormat = s.autoDetectFormat
      ),
      createdAt = s.createdAt,
      updatedAt = s.updatedAt
    )
