package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.viewer.models.DiagramId


/** The rule that changes a diagram id into a file name in the library.
  *
  * This is a CONTRACT, not a detail. `gx` writes `diagrams/<name>.json` on the
  * JVM. Under D7.3, the webview writes the same directory through the library
  * commands of the desktop. If the two used different names for one id, a
  * diagram that the user edits in the UI would silently become a second
  * diagram.
  *
  * Thus the rule is here, one time, and both hosts call it. A copy of the rule
  * in Rust is not permitted, because a copy of this type caused the
  * content-hash divergence of V-13. The Rust shell does NOT get this rule, on
  * purpose. It only makes sure that a name it gets stays inside the directory.
  * That containment check is a different question, and it is safe to answer
  * it separately.
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
