package org.jpablo.graphexplorer.viewer.formats.dot

object TextUtils:
  def escape(userText: String): String =
    userText
      // escape single slashes first; this will ignore newlines
      .replaceAll("""\\""", """\\\\""")
      // replace '\n' (single character) with two characters: ['\\', 'n']
      .replaceAll("\n", """\\n""")

  // Single left-to-right scan: sequential replaceAll calls corrupt text where the two
  // escapes interact (e.g. the literal text `\n`, stored as `\\n`, first collapsed to
  // `\n` and then wrongly turned into a line break — so unescape(escape(x)) != x).
  def unescape(dotText: String): String =
    val sb = StringBuilder()
    var i  = 0
    while i < dotText.length do
      val c = dotText.charAt(i)
      if c == '\\' && i + 1 < dotText.length then
        dotText.charAt(i + 1) match
          case '\\'  => sb.append('\\')
          case 'n'   => sb.append('\n')
          case other => sb.append('\\').append(other)
        i += 2
      else
        sb.append(c)
        i += 1
    sb.toString
