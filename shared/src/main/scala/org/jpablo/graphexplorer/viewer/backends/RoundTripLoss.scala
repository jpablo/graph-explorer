package org.jpablo.graphexplorer.viewer.backends

/** Content that a document can have and that the graph model does not keep.
  *
  * A canvas edit prints the whole graph again, and the printers write only
  * what the graph models. So the new text does not have this content.
  *
  * @param label
  *   the name of the content in a message to the person.
  */
enum UnmodeledContent(val label: String) derives CanEqual:

  /** DOT line comments, block comments and `#` lines. Mermaid `%%` lines, and
    * `#` lines in the front matter.
    */
  case Comments extends UnmodeledContent("the comments")

  /** The DOT `strict` keyword in front of the graph declaration. */
  case StrictKeyword extends UnmodeledContent("the `strict` keyword")

  /** Mermaid `%%{...}%%` directives, and front-matter keys other than `title`. */
  case Directives extends UnmodeledContent("the configuration directives (`%%{...}%%` or front matter)")

  /** Mermaid `click` lines. */
  case ClickHandlers extends UnmodeledContent("the `click` lines")

  /** Mermaid `accTitle` and `accDescr`. */
  case AccessibilityText extends UnmodeledContent("the accessibility text (`accTitle`, `accDescr`)")

  /** A Mermaid `direction` line inside a subgraph. */
  case SubgraphDirection extends UnmodeledContent("the `direction` lines in subgraphs")

/** Finds the content that a round trip through the graph model drops.
  *
  * The viewer uses it to refuse a canvas edit that would drop content from a
  * file. A false alarm blocks canvas edits on a document that has nothing to
  * lose, so the scan follows the lexical rules of each language: a comment
  * marker inside a quoted string or an HTML label is text, not a comment.
  *
  * The scan reads the text only, and does not parse the diagram. So it stays
  * cheap enough to run on each canvas edit.
  */
object RoundTripLoss:

  def scan(text: String, format: DiagramFormat): Set[UnmodeledContent] =
    format match
      case DiagramFormat.DOT     => DotLoss.scan(text)
      case DiagramFormat.Mermaid => MermaidLoss.scan(text)

  /** The DOT lexer rules that matter here, as the parsers apply them:
    *
    *   - `//` starts a line comment, and a slash-star pair starts a block comment.
    *   - `#` starts a comment to the end of the line. Graphviz accepts it only
    *     at the start of a line. The Scala parser accepts it at each token
    *     boundary, and it is not part of a token in either parser.
    *   - A quoted string ends at the next `"` that no `\` escapes.
    *   - An HTML string starts at `<` and ends at the `>` that balances it. It
    *     can contain quotes and comment markers as text.
    */
  private object DotLoss:

    def scan(text: String): Set[UnmodeledContent] =
      val n          = text.length
      var comments   = false
      var firstToken = Option.empty[String]
      var i          = 0

      def at(k: Int): Char = if k < n then text.charAt(k) else '\u0000'
      def lineEnd(from: Int): Int =
        val end = text.indexOf('\n', from)
        if end < 0 then n else end

      while i < n do
        val c = text.charAt(i)
        if c.isWhitespace then i += 1
        else if c == '#' || (c == '/' && at(i + 1) == '/') then
          comments = true
          i = lineEnd(i)
        else if c == '/' && at(i + 1) == '*' then
          comments = true
          val end = text.indexOf("*/", i + 2)
          i = if end < 0 then n else end + 2
        else
          val end =
            if c == '"' then quotedEnd(text, i + 1)
            else if c == '<' then htmlEnd(text, i)
            else if isIdChar(c) then identifierEnd(text, i)
            else i + 1
          if firstToken.isEmpty then firstToken = Some(text.substring(i, end))
          i = end

      // `strict` can only be the first token: a DOT document starts with the
      // graph declaration.
      val strict = firstToken.exists(_.equalsIgnoreCase("strict"))
      Set(
        Option.when(comments)(UnmodeledContent.Comments),
        Option.when(strict)(UnmodeledContent.StrictKeyword)
      ).flatten

    private def isIdChar(c: Char): Boolean = c == '_' || c.isLetterOrDigit || c >= '\u0080'

    private def identifierEnd(text: String, from: Int): Int =
      var i = from
      while i < text.length && isIdChar(text.charAt(i)) do i += 1
      i

    /** The index after the closing quote. `from` is the index after the opening quote. */
    private def quotedEnd(text: String, from: Int): Int =
      var i = from
      var closed = false
      while i < text.length && !closed do
        text.charAt(i) match
          case '\\' => i += 2
          case '"'  => i += 1; closed = true
          case _    => i += 1
      i min text.length

    /** The index after the `>` that balances the `<` at `from`. */
    private def htmlEnd(text: String, from: Int): Int =
      var i     = from
      var depth = 0
      var done  = false
      while i < text.length && !done do
        text.charAt(i) match
          case '<' => depth += 1
          case '>' => depth -= 1; done = depth == 0
          case _   => ()
        i += 1
      i

  /** The Mermaid rules that matter here. Mermaid reads these constructs one
    * line at a time:
    *
    *   - A comment is a line that starts with `%%`. A `%%` in other positions,
    *     for example inside a label, is text.
    *   - A directive starts with `%%{` and ends with `}%%`, and it can continue
    *     on the lines that follow.
    *   - `click`, `accTitle`, `accDescr` and `direction` are keywords at the
    *     start of a statement. A `;` also separates two statements.
    */
  private object MermaidLoss:

    private val Click       = """(?i)click\s+[A-Za-z0-9_].*""".r
    private val Accessible  = """(?i)acc(?:Title|Descr)\s*[:{].*""".r
    private val AccDescrTop = """(?i)accDescr\s*\{.*""".r
    private val Subgraph    = """(?i)subgraph(?:\s.*)?""".r
    private val End         = """end(?:[\s;].*)?""".r
    private val Direction   = """(?i)direction\s+(?:TB|TD|BT|LR|RL)\s*;?""".r

    def scan(text: String): Set[UnmodeledContent] =
      val found = Set.newBuilder[UnmodeledContent]
      val lines = text.linesIterator.map(_.trim).toVector

      // The graph keeps only the `title` of the front matter, so the printer
      // writes no other key back.
      val bodyStart =
        if !lines.headOption.contains("---") then 0
        else
          val closing = lines.indexWhere(_ == "---", 1)
          if closing < 0 then 0
          else
            lines.slice(1, closing).filter(_.nonEmpty).foreach: line =>
              if line.startsWith("#") then found += UnmodeledContent.Comments
              else if !line.startsWith("title:") then found += UnmodeledContent.Directives
            closing + 1

      var depth           = 0
      var inDirective     = false
      var inAccessibility = false

      lines.drop(bodyStart).foreach: line =>
        if inDirective then inDirective = !line.contains("}%%")
        else if inAccessibility then inAccessibility = !line.contains("}")
        else if line.startsWith("%%{") then
          found += UnmodeledContent.Directives
          inDirective = !line.drop(3).contains("}%%")
        else if line.startsWith("%%") then found += UnmodeledContent.Comments
        else
          statements(line).foreach:
            case Click()      => found += UnmodeledContent.ClickHandlers
            case Accessible() => found += UnmodeledContent.AccessibilityText
            case Subgraph()   => depth += 1
            case End()        => depth = 0 max (depth - 1)
            case Direction()  => if depth > 0 then found += UnmodeledContent.SubgraphDirection
            case _            => ()
          // A multi-line `accDescr { ... }` block continues to the line with `}`.
          inAccessibility = line match
            case AccDescrTop() => !line.drop(line.indexOf('{') + 1).contains("}")
            case _             => false

      found.result()

    /** The statements of a line: its parts between the `;` separators that
      * are not inside a quoted string. Each part is trimmed.
      */
    private def statements(line: String): List[String] =
      val parts   = List.newBuilder[String]
      val current = StringBuilder()
      var quoted  = false
      line.foreach: c =>
        if c == '"' then quoted = !quoted
        if c == ';' && !quoted then
          parts += current.toString.trim
          current.clear()
        else current += c
      parts += current.toString.trim
      parts.result().filter(_.nonEmpty)
