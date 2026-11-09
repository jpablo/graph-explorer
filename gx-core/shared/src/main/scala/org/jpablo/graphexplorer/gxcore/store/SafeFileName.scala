package org.jpablo.graphexplorer.gxcore.store

import scala.annotation.tailrec

/** The rule that changes the name of a diagram into a file name that each
  * file system accepts: macOS, Linux and Windows.
  *
  * The migration uses it for the old records that have no file. The
  * browser-to-desktop migration can use it in the same way (plan §4).
  *
  * The rule keeps letters outside ASCII, so that the person can find the file
  * by its name. It changes to `_` each character that Windows refuses, and
  * each control character.
  */
object SafeFileName:

  /** The name of a file whose diagram has no name. */
  val Fallback = "Untitled"

  /** The limit of a file name is 255 bytes. This leaves space for ` 999` and
    * an extension.
    */
  private val MaxBytes = 200

  private val Forbidden: Set[Char] = "/\\:*?\"<>|".toSet

  /** Windows refuses these names, also with an extension (`CON.dot`). */
  private val Reserved: Set[String] =
    Set("CON", "PRN", "AUX", "NUL") ++ (1 to 9).flatMap(i => List(s"COM$i", s"LPT$i"))

  /** The base of a file name, with no extension. */
  def of(name: String): String =
    val replaced = name.map(c => if c.isControl || Forbidden.contains(c) then '_' else c)
    val cut      = trimEnds(cutToBytes(trimEnds(replaced), MaxBytes))
    val base     = if cut.isEmpty then Fallback else cut
    if Reserved.contains(base.takeWhile(_ != '.').trim.toUpperCase) then base + "_" else base

  /** `Name`, `Name 2`, `Name 3`, and so on. */
  def numbered(base: String, n: Int): String = if n == 1 then base else s"$base $n"

  /** Remove the dots and the spaces at the two ends. A dot at the start makes
    * a hidden file, and Windows removes a dot or a space at the end.
    */
  private def trimEnds(s: String): String =
    def drop(t: String) = t.dropWhile(c => c == '.' || c.isWhitespace)
    drop(drop(s).reverse).reverse

  /** The longest start of `s` whose UTF-8 form has `max` bytes or fewer. The
    * cut is between two characters, never inside one.
    */
  private def cutToBytes(s: String, max: Int): String =
    @tailrec
    def loop(i: Int, bytes: Int): Int =
      if i >= s.length then i
      else
        val cp    = s.codePointAt(i)
        val width = if cp < 0x80 then 1 else if cp < 0x800 then 2 else if cp < 0x10000 then 3 else 4
        if bytes + width > max then i else loop(i + Character.charCount(cp), bytes + width)
    s.substring(0, loop(0, 0))
