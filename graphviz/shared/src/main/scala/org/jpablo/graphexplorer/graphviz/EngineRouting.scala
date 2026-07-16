package org.jpablo.graphexplorer.graphviz

/** Which layout engine a DOT source asks for — the routing predicate between
  * the pure-Scala port (ONLY the `dot` engine is implemented) and viz-js
  * (every other engine: neato/fdp/sfdp/twopi/circo/osage/patchwork).
  *
  * Shared (JVM + JS): the viewer routes renders with it, and the JVM example
  * gate uses it to decide which shipped examples the port must reproduce.
  */
object EngineRouting:

  /** The `layout` graph attribute → engine name (e.g. `layout=neato`,
    * `layout = "twopi"`, `layout="neato"` — the VALUE may be quoted, which is
    * the common DOT style; the `"?` consumes the opening quote). */
  private val layoutAttr = """(?i)\blayout\b\s*=\s*"?\s*([A-Za-z]+)""".r
  private val quotedStr  = """(?s)"(?:[^"\\]|\\.)*"""".r

  /** Blank `//…`, line-leading `#…`, and `/*…*/` comments with spaces —
    * string-aware (comment markers inside a quoted string are content, e.g.
    * a `#rrggbb` color), preserving offsets and the strings themselves. */
  private def blankComments(s: String): String =
    val b = new StringBuilder(s)
    var i           = 0
    var inStr       = false
    var atLineStart = true
    while i < s.length do
      val c = s.charAt(i)
      if inStr then
        if c == '\\' && i + 1 < s.length then i += 2
        else
          if c == '"' then inStr = false
          i += 1
      else if c == '"' then { inStr = true; atLineStart = false; i += 1 }
      else if c == '/' && i + 1 < s.length && s.charAt(i + 1) == '/' then
        while i < s.length && s.charAt(i) != '\n' do { b.setCharAt(i, ' '); i += 1 }
      else if c == '/' && i + 1 < s.length && s.charAt(i + 1) == '*' then
        var closed = false
        while i < s.length && !closed do
          if s.charAt(i) == '*' && i + 1 < s.length && s.charAt(i + 1) == '/' then
            b.setCharAt(i, ' '); b.setCharAt(i + 1, ' '); i += 2; closed = true
          else { b.setCharAt(i, ' '); i += 1 }
      else if c == '#' && atLineStart then
        while i < s.length && s.charAt(i) != '\n' do { b.setCharAt(i, ' '); i += 1 }
      else
        if c == '\n' then atLineStart = true
        else if !c.isWhitespace then atLineStart = false
        i += 1
    b.toString

  /** True when the graph uses the `dot` engine — the only one the Scala port
    * implements. `dot` and unset both route to the port; every other engine
    * routes to viz-js. Heuristic (no full parse), but comment-blind and
    * string-aware: the `layout` KEYWORD must sit outside any quoted string
    * (so a label mentioning `layout=dot` can't mis-route), while the VALUE
    * may be quoted (`layout="neato"` — the common style). */
  def usesDotEngine(dot: String): Boolean =
    val t = blankComments(dot)
    val strSpans = quotedStr.findAllMatchIn(t).map(m => (m.start, m.end)).toVector
    def insideString(i: Int): Boolean = strSpans.exists((s, e) => i > s && i < e)
    layoutAttr.findAllMatchIn(t).find(m => !insideString(m.start))
      .map(_.group(1).toLowerCase) match
      case Some(engine) => engine == "dot"
      case None         => true

end EngineRouting
