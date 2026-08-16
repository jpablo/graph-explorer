package org.jpablo.graphexplorer.gxcore.model

import java.nio.charset.StandardCharsets

/** What an origin's scheme is capable of, which bounds the legal [[SyncMode]]s.
  *
  * Registered rather than inferred, so adding `https:` or a database origin is a
  * row here plus a driver, not a search for every place that assumed `file:`.
  */
enum OriginScheme(val scheme: String, val canRead: Boolean, val canWrite: Boolean, val canWatch: Boolean)
    derives CanEqual:
  case File  extends OriginScheme("file", canRead = true, canWrite = true, canWatch = true)
  case Https extends OriginScheme("https", canRead = true, canWrite = false, canWatch = true)

object OriginScheme:
  def parse(scheme: String): Option[OriginScheme] =
    values.find(_.scheme == scheme.toLowerCase)

  extension (s: OriginScheme)
    def permits(mode: SyncMode): Boolean = mode match
      case SyncMode.Detached => true
      case SyncMode.Pull     => s.canRead
      case SyncMode.Push     => s.canWrite
      case SyncMode.Sync     => s.canRead && s.canWrite

    /** Rejected at bind time with a reason, never silently downgraded — a
      * binding that quietly became read-only would look like a broken save.
      */
    def rejectionFor(mode: SyncMode): Option[String] =
      Option.unless(s.permits(mode))(
        s"scheme '${s.scheme}' cannot support $mode " +
          s"(read=${s.canRead}, write=${s.canWrite}, watch=${s.canWatch})"
      )

/** The canonical identity of an origin.
  *
  * This is the join key between the library, the watch registry and the CLI, so
  * two spellings of one thing MUST produce one value here or the system grows a
  * second entry for the same file and the two fight over it in `Sync` mode.
  *
  * v1 lost five months to exactly this hazard while URIs were still an
  * incidental detail (`desktop/src-tauri/src/main.rs:921-934`): a hand-rolled
  * decoder replaced `%2F` and nothing else, which happened to cover a plain
  * POSIX path and no path containing a space, and no Windows path at all. Hence
  * one encoder, one decoder, both tested, and no string concatenation anywhere
  * else.
  *
  * Resolving a relative path and following symlinks needs a filesystem and lives
  * on the JVM side (`FileOrigins`); everything here is pure so the viewer can
  * hold and compare origins without one.
  */
opaque type OriginUri = String

object OriginUri:
  /** Characters RFC 3986 calls unreserved, which never need escaping. */
  private def isUnreserved(c: Char): Boolean =
    (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
      c == '-' || c == '.' || c == '_' || c == '~'

  /** Percent-encode one path segment's worth of text, UTF-8 first.
    *
    * Non-ASCII must be encoded as its UTF-8 bytes, not its UTF-16 code units —
    * getting that wrong is invisible until someone names a file in a language
    * other than English.
    */
  private def encodeSegment(segment: String): String =
    val out = new StringBuilder(segment.length)
    for byte <- segment.getBytes(StandardCharsets.UTF_8) do
      val c = (byte & 0xff).toChar
      if isUnreserved(c) then out.append(c)
      else out.append("%%%02X".format(byte & 0xff))
    out.toString

  private def decodeToBytes(encoded: String): Array[Byte] =
    val out = new scala.collection.mutable.ArrayBuffer[Byte](encoded.length)
    var i   = 0
    while i < encoded.length do
      encoded.charAt(i) match
        case '%' if i + 2 < encoded.length =>
          out += Integer.parseInt(encoded.substring(i + 1, i + 3), 16).toByte
          i += 3
        case c =>
          // Already-unescaped characters are ASCII by construction; anything
          // else would mean the URI was never encoded by `encodeSegment`.
          out ++= c.toString.getBytes(StandardCharsets.UTF_8)
          i += 1
    out.toArray

  /** Build a `file:` URI from an ALREADY canonical absolute path.
    *
    * Canonicalization — resolving `..`, following symlinks, and recovering the
    * filesystem's true casing — happens before this, on the JVM side. Passing a
    * non-canonical path in produces a valid URI for the wrong identity, which is
    * precisely the failure this type exists to prevent, so the JVM entry point
    * is the only supported way in.
    */
  private[gxcore] def fromCanonicalPath(absolutePath: String): OriginUri =
    // Windows arrives as `C:\Users\x`, sometimes behind the `\\?\` verbatim
    // prefix that `toRealPath` adds for long paths. Both separators and the
    // prefix would be percent-encoded by a naive encoder, which is what broke
    // v1 on Windows.
    val stripped  = absolutePath.stripPrefix("\\\\?\\")
    val slashed   = stripped.replace('\\', '/')
    val rooted    = if slashed.startsWith("/") then slashed else "/" + slashed
    val encoded   = rooted.split("/", -1).map(encodeSegment).mkString("/")
    // The drive-letter colon is conventionally left bare in file URIs; encoding
    // it produces a URI no other tool will recognise.
    val restored  = "^/([A-Za-z])%3A".r.replaceAllIn(encoded, m => s"/${m.group(1)}:")
    s"file://$restored"

  /** Parse a URI that is already canonical (round-tripped from this encoder or
    * received over the wire).
    */
  def parse(raw: String): Either[String, OriginUri] =
    raw.indexOf(':') match
      case -1 => Left(s"not a URI (no scheme): $raw")
      case i =>
        val scheme = raw.substring(0, i)
        OriginScheme.parse(scheme) match
          case None    => Left(s"unsupported scheme '$scheme' in: $raw")
          case Some(_) => Right(raw)

  extension (u: OriginUri)
    def value: String = u

    def scheme: OriginScheme =
      OriginScheme.parse(u.substring(0, u.indexOf(':'))).getOrElse(
        // Unreachable: construction goes through `parse` or `fromCanonicalPath`.
        throw IllegalStateException(s"OriginUri built with an unknown scheme: $u")
      )

    /** The filesystem path a `file:` URI denotes, or None for other schemes. */
    def filePath: Option[String] =
      Option.when(u.startsWith("file://")):
        val encoded = u.stripPrefix("file://")
        val decoded = String(decodeToBytes(encoded), StandardCharsets.UTF_8)
        // `/C:/Users/x` is a Windows path wearing a URI's leading slash.
        if "^/[A-Za-z]:".r.findPrefixOf(decoded).isDefined then decoded.substring(1)
        else decoded

  given CanEqual[OriginUri, OriginUri] = CanEqual.derived

  /** Stored as the canonical string. Reading does NOT re-canonicalize: a record
    * written on one machine may name a path that does not exist on this one, and
    * silently rewriting it would change the identity the record is keyed by.
    */
  given upickle.default.ReadWriter[OriginUri] =
    upickle.default.readwriter[String].bimap[OriginUri](_.value, identity)
