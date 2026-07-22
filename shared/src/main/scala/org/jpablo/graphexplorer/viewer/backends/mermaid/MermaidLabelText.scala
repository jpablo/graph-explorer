package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.formats.dot.TextUtils

/** Translates label text between the stored form and Mermaid's form.
  *
  * Labels are stored DOT-escaped (`\n` is a line break, `\\` a literal backslash — see
  * formats.dot.TextUtils), which Graphviz honors but Mermaid renders literally: Mermaid's
  * line-break syntax is `<br/>`. These two functions are exact inverses so labels survive
  * a Mermaid round-trip unchanged.
  */
object MermaidLabelText:

  private val LineBreakTag = "(?i)<br\\s*/?>".r

  /** Stored (DOT-escaped) label -> Mermaid label text: `\n` becomes `<br/>` (as do the
    * justified variants `\l`/`\r`, which Mermaid cannot express), `\\` becomes a literal
    * backslash. Unknown escapes pass through untouched.
    */
  def fromStored(stored: String): String =
    val sb = StringBuilder()
    var i  = 0
    while i < stored.length do
      val c = stored.charAt(i)
      if c == '\\' && i + 1 < stored.length then
        stored.charAt(i + 1) match
          case '\\'            => sb.append('\\')
          case 'n' | 'l' | 'r' => sb.append("<br/>")
          case other           => sb.append('\\').append(other)
        i += 2
      else
        sb.append(c)
        i += 1
    sb.toString

  /** Mermaid label text -> stored (DOT-escaped) form: `<br/>` variants (`<br>`, `<br />`,
    * any case) become the `\n` escape; literal backslashes are escaped so text like a
    * verbatim `\n` in Mermaid source stays verbatim.
    */
  def toStored(mermaidText: String): String =
    TextUtils.escape(LineBreakTag.replaceAllIn(mermaidText, "\n"))
