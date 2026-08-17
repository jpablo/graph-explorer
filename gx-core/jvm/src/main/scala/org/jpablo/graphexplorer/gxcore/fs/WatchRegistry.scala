package org.jpablo.graphexplorer.gxcore.fs

import org.jpablo.graphexplorer.gxcore.model.{ContentHash, OriginUri}

import java.nio.file.{Path, Paths}
import scala.collection.mutable

/** Something changed underneath a watched origin. */
enum WatchEvent derives CanEqual:
  case Changed(uri: OriginUri, hash: ContentHash)

  /** The origin is gone. v1 read the file, ignored the failure, and emitted
    * nothing at all — a deleted watched file produced silence, which is the
    * worst possible answer because the UI keeps showing a diagram whose source
    * no longer exists. Carries the last known hash so the caller can say what
    * was lost and offer to restore it (V-06).
    */
  case Deleted(uri: OriginUri, lastKnown: ContentHash)

  /** A deleted origin came back — commonly an editor that writes by delete +
    * create rather than rename.
    */
  case Restored(uri: OriginUri, hash: ContentHash)

/** Watches origins by polling, and tells you what actually changed.
  *
  * Polling rather than filesystem events, inherited from v1 for the reason the
  * brief records: the 15ms/50ms tuning exists to hold a disk-to-UI budget on
  * oversubscribed CI runners, and it is measurable. Moving to FS events later is
  * fine, as long as the budget stays measured — the interface here does not
  * assume polling, only that something calls [[poll]].
  *
  * [[poll]] is public and explicit so behaviour can be tested without sleeping:
  * timing-dependent tests are how a debounce bug becomes an intermittent CI
  * failure nobody can reproduce.
  *
  * One entry per canonical URI, per §5.4 — a file backing three diagrams is
  * polled once and fans out to three, rather than being polled three times.
  */
final class WatchRegistry(
    audit:      Audit,
    debounceMs: Long = 50L,
    now:        () => Long = () => System.currentTimeMillis()
):

  private final class Entry:
    /** The content the caller has already been told about. None = the origin is
      * currently absent, as far as anyone downstream knows.
      */
    var notified: Option[ContentHash] = None

    /** Seen but not yet settled: (hash, when it first appeared). */
    var pending: Option[(ContentHash, Long)] = None

    /** Content this process just wrote, awaiting its own echo (V-05). */
    var selfWrite: Option[ContentHash] = None

  private val entries = mutable.LinkedHashMap.empty[OriginUri, Entry]

  /** Begin watching. Idempotent: watching an already-watched origin is a no-op
    * rather than a second poller.
    */
  def watch(uri: OriginUri): Unit =
    synchronized:
      if !entries.contains(uri) then
        val entry = Entry()
        entry.notified = pathOf(uri).flatMap(Documents.hashOf)
        entries.put(uri, entry)
        audit.record(AuditEvent.WatchAdded(uri.value))

  def unwatch(uri: OriginUri): Boolean =
    synchronized:
      val removed = entries.remove(uri).isDefined
      if removed then audit.record(AuditEvent.WatchRemoved(uri.value))
      removed

  def watched: Vector[OriginUri] = synchronized(entries.keys.toVector)

  def isWatched(uri: OriginUri): Boolean = synchronized(entries.contains(uri))

  /** Declare that THIS process just wrote `hash` to `uri`, so the resulting
    * change is not reported back to its own author (V-05).
    *
    * Keyed by content, not by path or mtime, and that choice is what makes it
    * work: an mtime-based guard cannot tell our write from someone else's write
    * in the same millisecond, and a path-based guard suppresses everything.
    * Content is the only fingerprint that is exactly as specific as the event.
    */
  def noteSelfWrite(uri: OriginUri, hash: ContentHash): Unit =
    synchronized(entries.get(uri).foreach(_.selfWrite = Some(hash)))

  /** Sample every watched origin and return what settled since the last call. */
  def poll(): Vector[WatchEvent] =
    synchronized:
      val events = Vector.newBuilder[WatchEvent]
      val at     = now()
      for (uri, entry) <- entries do
        val current = pathOf(uri).flatMap(Documents.hashOf)
        current match
          case None =>
            // Deletion is reported immediately rather than debounced: an absent
            // file is not a transient state worth coalescing, and a rename-based
            // editor's brief gap is better surfaced and then corrected by a
            // Restored than swallowed.
            entry.notified.foreach: last =>
              events += WatchEvent.Deleted(uri, last)
              audit.record(AuditEvent.OriginMissing(uri.value))
            entry.notified = None
            entry.pending = None
            entry.selfWrite = None

          case Some(hash) =>
            val wasAbsent = entry.notified.isEmpty
            if entry.selfWrite.contains(hash) then
              // Our own echo: adopt it silently.
              entry.selfWrite = None
              entry.notified = Some(hash)
              entry.pending = None
            else if entry.notified.contains(hash) then entry.pending = None
            else
              // Any observation of content that is not ours invalidates a
              // pending self-write record. Without this the entry could outlive
              // its write and suppress a genuine, coincidentally identical
              // change much later.
              entry.selfWrite = None
              entry.pending match
                case Some((pendingHash, firstSeen))
                    if pendingHash == hash && at - firstSeen >= debounceMs =>
                  events += (if wasAbsent then WatchEvent.Restored(uri, hash)
                             else WatchEvent.Changed(uri, hash))
                  entry.notified = Some(hash)
                  entry.pending = None
                case Some((pendingHash, _)) if pendingHash == hash => () // still settling
                case _                                             => entry.pending = Some((hash, at))
      events.result()

  private def pathOf(uri: OriginUri): Option[Path] = uri.filePath.map(Paths.get(_))
