package org.jpablo.graphexplorer.gxcore.fs

import org.jpablo.graphexplorer.gxcore.model.ContentHash

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.util.control.NonFatal

/** Something worth being able to reconstruct afterwards. */
enum AuditEvent derives CanEqual:
  case Allowed(path: String, action: String)
  case Denied(path: String, reason: String)
  case Written(path: String, hash: ContentHash, source: String)
  case Conflict(path: String, expected: ContentHash, actual: ContentHash, source: String)
  case WatchAdded(uri: String)
  case WatchRemoved(uri: String)
  case OriginMissing(path: String)

/** Append-only JSONL record of privileged operations (V-08).
  *
  * §8 of the architecture doc asks whether anyone reads this, and notes that an
  * audit log nobody reads is cost without benefit. It is kept for one concrete
  * reason: when a diagram turns up with content the user did not write, the
  * question is *which* writer did it and when — and `source` is the only place
  * that is recorded. If `gx audit` never materialises, cut it.
  *
  * Writes are best-effort by construction. Failing to record an action must
  * never fail the action: a full disk should not make the editor read-only.
  */
final class Audit(path: Path):
  private val lock = Object()

  def record(event: AuditEvent): Unit =
    lock.synchronized:
      try
        Option(path.getParent).foreach(Files.createDirectories(_))
        val line = Audit.toJson(event) + "\n"
        Files.write(
          path,
          line.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
        )
        Audit.restrictToOwner(path)
      catch case NonFatal(_) => () // never fail the operation being audited

  def entries: Vector[String] =
    try
      if Files.exists(path) then Files.readAllLines(path, StandardCharsets.UTF_8).toArray.map(_.toString).toVector
      else Vector.empty
    catch case NonFatal(_) => Vector.empty

object Audit:
  /** The log records who touched which files, so it is owner-only — unlike the
    * user's own diagrams, which V-03 exists to stop us re-permissioning.
    */
  private def restrictToOwner(path: Path): Unit =
    try Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    catch case NonFatal(_) => ()

  private def escape(s: String): String =
    val out = StringBuilder(s.length + 2)
    s.foreach:
      case '"'                       => out ++= "\\\""
      case '\\'                      => out ++= "\\\\"
      case '\n'                      => out ++= "\\n"
      case '\r'                      => out ++= "\\r"
      case '\t'                      => out ++= "\\t"
      case c if c.toInt < 0x20       => out ++= f"\\u${c.toInt}%04x"
      case c                         => out += c
    out.toString

  private def obj(fields: (String, String)*): String =
    fields.map((k, v) => s""""$k":"${escape(v)}"""").mkString("{", ",", "}")

  private[fs] def toJson(event: AuditEvent): String =
    import AuditEvent.*
    val base = event match
      case Allowed(path, action)   => obj("event" -> "allowed", "path" -> path, "action" -> action)
      case Denied(path, reason)    => obj("event" -> "denied", "path" -> path, "reason" -> reason)
      case Written(path, hash, by) =>
        obj("event" -> "written", "path" -> path, "hash" -> hash.hex, "source" -> by)
      case Conflict(path, expected, actual, by) =>
        obj(
          "event"    -> "conflict",
          "path"     -> path,
          "expected" -> expected.hex,
          "actual"   -> actual.hex,
          "source"   -> by
        )
      case WatchAdded(uri)     => obj("event" -> "watch.added", "uri" -> uri)
      case WatchRemoved(uri)   => obj("event" -> "watch.removed", "uri" -> uri)
      case OriginMissing(path) => obj("event" -> "origin.missing", "path" -> path)
    // Timestamp is prepended rather than threaded through every case.
    s"""{"timestampMs":${System.currentTimeMillis()},${base.drop(1)}"""
