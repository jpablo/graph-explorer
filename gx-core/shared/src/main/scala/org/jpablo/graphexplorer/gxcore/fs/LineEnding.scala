package org.jpablo.graphexplorer.gxcore.fs

/** The newline convention a file uses.
  *
  * V-04 reads like politeness — don't reformat the user's file — but under D1 it
  * is load-bearing. A revision IS the hash of the bytes, so writing LF into a
  * CRLF file changes every line's bytes and therefore the document's identity.
  * The other side would see a content change on a save that altered nothing the
  * user typed, and a Windows editor writing CRLF back would change it again. Two
  * tools could bounce a diagram between two hashes forever, each seeing the
  * other as having edited it.
  *
  * So: detect what the file uses, and reproduce it.
  *
  * Shared rather than JVM-only, and still in `fs` because a line ending is a
  * property of BYTES on disk. The migration (`MetadataMigration`) needs it to
  * compare the text of an old record with its file, and the migration also
  * runs in the desktop page, which is Scala.js. Nothing here touches a file:
  * detection reads a String and `applyTo` returns one.
  */
enum LineEnding(val chars: String) derives CanEqual:
  case Lf   extends LineEnding("\n")
  case Crlf extends LineEnding("\r\n")

object LineEnding:
  /** Detect the dominant convention.
    *
    * Dominant rather than first-seen, because mixed files exist — a generator
    * appending LF to a CRLF file, say — and the majority is the convention worth
    * preserving. LF wins ties and empty files: it is what a file with no
    * newlines will get if one is ever added, and the safer default on the two
    * platforms of three that use it natively.
    */
  def detect(text: String): LineEnding =
    var crlf = 0
    var lf   = 0
    var i    = 0
    while i < text.length do
      if text.charAt(i) == '\n' then
        if i > 0 && text.charAt(i - 1) == '\r' then crlf += 1 else lf += 1
      i += 1
    if crlf > lf then Crlf else Lf

  /** Give `text` the end of `file`: one final line ending when `file` ends
    * with a line ending, and no final line ending when `file` does not. Only
    * the end of `text` changes.
    *
    * This is V-04 for the last byte. A writer that prints a whole diagram
    * gives no final newline. Without this rule, a change to one attribute
    * also changed the end of the file, and a diff or a tool that watches the
    * file saw a change that nobody made.
    */
  def withEndOf(file: String, text: String): String =
    val bare = text.reverse.dropWhile(c => c == '\n' || c == '\r').reverse
    if !file.endsWith("\n") then bare
    else if text.endsWith("\n") then text
    else text + "\n"

  extension (e: LineEnding)
    /** Rewrite text to use this convention, whatever it currently uses. */
    def applyTo(text: String): String =
      val normalized = text.replace("\r\n", "\n")
      e match
        case Lf   => normalized
        case Crlf => normalized.replace("\n", "\r\n")
