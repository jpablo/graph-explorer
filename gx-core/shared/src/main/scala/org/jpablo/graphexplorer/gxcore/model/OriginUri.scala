package org.jpablo.graphexplorer.gxcore.model

import java.nio.charset.StandardCharsets

/** The canonical identity of an origin.
  *
  * This is the join key between the library, the watch registry and the CLI.
  * Thus two spellings of one file MUST give one value here. If not, the library
  * gets two entries for one file, and each entry has different metadata.
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

  /** Parse a `file:` URI that is already canonical: one that this encoder
    * made, or one that came over the wire.
    *
    * Only a `file:` URI parses. The old records could name an `https:` origin,
    * but the library now keeps only files (ADR 0001). The migration reads such
    * an origin as "not a file".
    */
  def parse(raw: String): Either[String, OriginUri] =
    if raw.startsWith("file://") then Right(raw)
    else
      raw.indexOf(':') match
        case -1 => Left(s"not a URI (no scheme): $raw")
        case i  => Left(s"not a file URI (scheme '${raw.substring(0, i)}'): $raw")

  extension (u: OriginUri)
    def value: String = u

    /** The file system path of the URI. It is `None` only for a value that
      * does not start with `file://`, and `parse` refuses such a value.
      *
      * CAUTION: on Windows, this is NOT the inverse of `fromCanonicalPath`. That
      * encoder writes URI separators, so `C:\\Users\\x\\a.dot` comes back as
      * `C:/Users/x/a.dot`. It is the same file, in the other form.
      *
      * Give the result to `Paths.get`, which accepts both separators. Each
      * caller here does that. Do NOT compare the result with a path from a
      * different process. The desktop shell writes the separator of the
      * platform, so a byte comparison fails, and it fails SILENTLY. Compare
      * the keys of the two paths ([[LibraryEntry.keySource]]) instead.
      *
      * `OriginUriSpec` tests this shape.
      */
    def filePath: Option[String] =
      Option.when(u.startsWith("file://")):
        val encoded = u.stripPrefix("file://")
        val decoded = String(decodeToBytes(encoded), StandardCharsets.UTF_8)
        // `/C:/Users/x` is a Windows path wearing a URI's leading slash.
        if "^/[A-Za-z]:".r.findPrefixOf(decoded).isDefined then decoded.substring(1)
        else decoded

  given CanEqual[OriginUri, OriginUri] = CanEqual.derived
