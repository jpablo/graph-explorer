package org.jpablo.graphexplorer.gxcore.fs

import org.jpablo.graphexplorer.gxcore.model.{ContentHash, OriginUri}

import java.nio.file.Path
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
  *
  * The registry reports each change, also a change that this process wrote.
  * Its only user is `gx watch`, which writes nothing. The desktop keeps the
  * hashes of its own writes and so does not report them (V-05).
  */
final class WatchRegistry(
    audit:      Audit,
    debounceMs: Long = 50L,
    now:        () => Long = () => System.currentTimeMillis()
):

  /** `path` is None when the URI names no local file. */
  private final class Entry(val path: Option[Path]):
    /** The content the caller has already been told about. None = the origin is
      * currently absent, as far as anyone downstream knows.
      */
    var notified: Option[ContentHash] = None

    /** Seen but not yet settled: (hash, when it first appeared). */
    var pending: Option[(ContentHash, Long)] = None

  private val entries = mutable.LinkedHashMap.empty[OriginUri, Entry]

  /** Begin watching. Idempotent: watching an already-watched origin is a no-op
    * rather than a second poller.
    */
  def watch(uri: OriginUri): Unit =
    synchronized:
      if !entries.contains(uri) then
        // An origin whose path no local file can have names no local file.
        // It stays watched, and a poll reports nothing for it.
        val entry = Entry(FileOrigins.pathOf(uri))
        entry.notified = entry.path.flatMap(Documents.hashOf)
        entries.put(uri, entry)
        audit.record(AuditEvent.WatchAdded(uri.value))

  def unwatch(uri: OriginUri): Boolean =
    synchronized:
      val removed = entries.remove(uri).isDefined
      if removed then audit.record(AuditEvent.WatchRemoved(uri.value))
      removed

  def watched: Vector[OriginUri] = synchronized(entries.keys.toVector)

  /** Sample every watched origin and return what settled since the last call. */
  def poll(): Vector[WatchEvent] =
    synchronized:
      val events = Vector.newBuilder[WatchEvent]
      val at     = now()
      for (uri, entry) <- entries do
        val current = entry.path.flatMap(Documents.hashOf)
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

          case Some(hash) =>
            if entry.notified.contains(hash) then entry.pending = None
            else
              entry.pending match
                case Some((pendingHash, firstSeen))
                    if pendingHash == hash && at - firstSeen >= debounceMs =>
                  events += (if entry.notified.isEmpty then WatchEvent.Restored(uri, hash)
                             else WatchEvent.Changed(uri, hash))
                  entry.notified = Some(hash)
                  entry.pending = None
                case Some((pendingHash, _)) if pendingHash == hash => () // still settling
                case _                                             => entry.pending = Some((hash, at))
      events.result()
