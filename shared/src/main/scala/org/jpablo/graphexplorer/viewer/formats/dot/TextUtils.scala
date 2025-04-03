package org.jpablo.graphexplorer.viewer.formats.dot

object TextUtils:
  def escape(userText: String): String =
    userText
      // escape single slashes first; this will ignore newlines
      .replaceAll("""\\""", """\\\\""")
      // replace '\n' (single character) with two characters: ['\\', 'n']
      .replaceAll("\n", """\\n""")

  def unescape(dotText: String): String =
    dotText
      .replaceAll(
        """\\\\""", // regex matching two backslashes
        """\\"""    // replaced by a single backslash
      )
      .replaceAll("""\\n""", "\n")
