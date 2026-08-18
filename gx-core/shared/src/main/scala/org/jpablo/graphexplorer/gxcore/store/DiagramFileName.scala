package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.gxcore.model.DiagramId

/** How a diagram id becomes a file name in the library.
  *
  * This is a CONTRACT, not a detail: `gx` writes `diagrams/<name>.json` on the
  * JVM, and under D7.3 the webview writes the same directory through the
  * desktop's library commands. If the two disagreed about the name for one id,
  * a diagram edited in the UI would silently become a second record.
  *
  * So the rule lives here, once, and both hosts call it — rather than being
  * transcribed into Rust, which is how V-13's content-hash divergence happened.
  * The Rust shell is deliberately NOT given this rule; it only checks that a
  * name it is handed stays inside the directory (a containment check, which is
  * a different question and safe to answer independently).
  */
object DiagramFileName:

  /** Deliberately `isLetterOrDigit` rather than an ASCII test: an id with
    * accented or CJK characters keeps them, so the file stays recognisable to
    * whoever is looking at the directory.
    */
  def of(id: DiagramId): String = of(id.value)

  def of(id: String): String =
    val cleaned = id.map(c => if c.isLetterOrDigit || c == '-' || c == '_' then c else '_')
    if cleaned.isEmpty then "_" else cleaned.take(120)

  /** The full basename, extension included — what the desktop's library
    * commands are addressed by.
    */
  def fileOf(id: DiagramId): String = s"${of(id)}.json"
