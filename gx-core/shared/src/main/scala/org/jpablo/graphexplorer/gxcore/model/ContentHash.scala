package org.jpablo.graphexplorer.gxcore.model

/** The identity of a document's bytes, and therefore its revision.
  *
  * v1 kept `revision: Long` as a counter in one process's `HashMap`, which is
  * why every operation had to be routed through that process: the number existed
  * nowhere else. Deriving it from content instead means any participant computes
  * the same value independently — `gx` with no desktop running, the desktop, a
  * later session after a crash — and a conditional write becomes compare-and-swap
  * on the bytes. See docs/desktop-gx-v2-architecture.md D1.
  *
  * Two consequences are deliberate rather than defects:
  *
  *   - identical content has identical identity, so an A -> B -> A sequence
  *     returns to the original hash. For conflict detection on a text file this
  *     is correct: if what I based my edit on is what is there now, my edit is
  *     safe.
  *   - a generator that rewrites a file to byte-identical content produces no
  *     change at all, which is the `Converged` row of [[SyncState]] and the
  *     difference between a live-updating diagram and a stream of false
  *     conflicts.
  *
  * The value is carried as lowercase hex so it can cross a wire, a JSON record
  * and a process boundary without an encoding decision at each hop.
  */
opaque type ContentHash = String

object ContentHash:
  /** Wrap an already-computed digest. Computing one needs a platform (see
    * `Hashing` on the JVM side); the model only ever compares them.
    */
  def fromHex(hex: String): ContentHash = hex.toLowerCase

  extension (h: ContentHash) def hex: String = h

  given CanEqual[ContentHash, ContentHash] = CanEqual.derived
