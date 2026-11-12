package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.graphviz.DotSourceScan

/** Shared source-text scanning helpers for the Mermaid*Fallback parsers, so the
  * directive-skip rules and label normalization cannot drift between fallbacks
  * (the whole-word prefix fix once had to be applied to two identical copies).
  * `private[backends]` (not `[mermaid]`) so DiagramFormat.detect can delegate to
  * [[looksLikeMermaid]] — the grammar catalogue belongs in this package.
  */
private[backends] object MermaidSourceScan:

  // Display name by lowercased header prefix, for every Mermaid diagram type
  // bundled with mermaid 11. ONE table for both detection ([[looksLikeMermaid]])
  // and naming ([[diagramKind]]), so a type we detect always has a name and vice
  // versa. The flowchart header `graph` is not in the table, because DOT also
  // starts with `graph`. [[declaresGraphFlowchart]] has its rule.
  private val KindByPrefix: List[(String, String)] = List(
    "flowchart"          -> "flowchart",
    "sequencediagram"    -> "sequence",
    "classdiagram"       -> "class",
    "statediagram"       -> "state",
    "erdiagram"          -> "ER",
    "journey"            -> "journey",
    "gantt"              -> "gantt",
    "pie"                -> "pie",
    "mindmap"            -> "mindmap",
    "timeline"           -> "timeline",
    "gitgraph"           -> "git",
    "quadrantchart"      -> "quadrant",
    "xychart"            -> "xy chart",
    "sankey"             -> "sankey",
    "requirementdiagram" -> "requirement",
    "c4context"          -> "C4",
    "c4container"        -> "C4",
    "c4component"        -> "C4",
    "c4dynamic"          -> "C4",
    "c4deployment"       -> "C4",
    "block-beta"         -> "block",
    "kanban"             -> "kanban",
    "packet"             -> "packet",
    "radar"              -> "radar",
    "architecture"       -> "architecture",
    "treemap"            -> "treemap",
    "zenuml"             -> "zenUML"
  )

  /** True when the text declares a Mermaid diagram: a header keyword we know, or
    * an `%%{…}%%` directive, which no other language here has.
    *
    * Takes the RAW text and does its own frontmatter and comment skipping. It
    * used to take a pre-lowercased string and test `---` as a prefix in its own
    * right, which made every Markdown document in the world a Mermaid diagram —
    * frontmatter is evidence of nothing until a header follows it.
    */
  def looksLikeMermaid(text: String): Boolean =
    diagramKind(text).isDefined || hasLeadingDirective(text)

  /** The diagram's kind (flowchart, sequence, class, ...): the header keyword
    * [[looksLikeMermaid]] detects, as a display name. Frontmatter and directive/
    * comment lines are skipped — the header may follow them. Same cheapness
    * contract as [[diagramTitle]]: shown per library card.
    */
  def diagramKind(text: String): Option[String] =
    headerOnward(text).flatMap: header =>
      if declaresGraphFlowchart(header) then Some("flowchart")
      else KindByPrefix.collectFirst { case (prefix, kind) if header.startsWith(prefix) => kind }

  /** The document from its header line onward, trimmed per line and lowercased;
    * `None` when it has no header at all (frontmatter and comments only).
    *
    * The header scans of this object use it to skip the frontmatter and the
    * comment lines in front of the header: [[diagramKind]] uses it, and the
    * other scans ask [[diagramKind]] for the kind of the diagram. RoundTripLoss
    * and MermaidFlowchartParser have their own frontmatter split.
    *
    * From the header ONWARD rather than the header LINE, so the brace test in
    * [[declaresGraphFlowchart]] can see a `{` that DOT put on the following line.
    */
  private def headerOnward(text: String): Option[String] =
    val lines = text.linesIterator.map(_.trim).take(50).toList
    val body = lines match
      case "---" :: rest => rest.dropWhile(_ != "---").drop(1)
      case _             => lines
    val header = body.indexWhere(l => l.nonEmpty && !l.startsWith("%%"))
    Option.when(header >= 0)(body.drop(header).mkString("\n").toLowerCase)

  /** A directive at the top is Mermaid's alone, and settles it even when the
    * header keyword that follows is one we do not know.
    */
  private def hasLeadingDirective(text: String): Boolean =
    text.linesIterator.map(_.trim).take(50).find(_.nonEmpty).exists(_.startsWith("%%{"))

  // The app knew these five forms first. They keep their earlier rule: a header
  // with no body after it is a flowchart too.
  private val FirstLetterDirections = Set("lr", "rl", "tb", "bt", "td")
  // The directions that the Mermaid 11.12 lexer reads after `graph`, lowercased as
  // the header is. A letter form must end where an identifier ends. An arrow form
  // can follow `graph` with no space (`graph>`). No letter form is the start of
  // another, thus the search order does not matter.
  private val LetterDirections = FirstLetterDirections ++ Set("br", "v")
  private val ArrowDirections  = Set('>', '<', '^')

  /** True when the lowercased `header` is a Mermaid flowchart that starts with `graph`.
    *
    * This is the one point where the two vocabularies collide: `graph LR { a -- b }`
    * is an UNDIRECTED DOT GRAPH NAMED LR, not a left-to-right flowchart. Mermaid
    * reads `graph` with no direction, or with one of LR, RL, TB, BT, TD, BR, `>`,
    * `<`, `^` and `v`. DOT reads `graph`, an optional name, and then `{`. Thus:
    *
    *   - A direction that an identifier character follows is a DOT name (`graph LRX`).
    *   - A `{` after the direction opens a DOT graph. A same-line Mermaid statement
    *     follows a `;`, never a `{`.
    *   - After a bare `graph` line, DOT can put the name on the next line
    *     (`graph\nG {`). [[bareGraphBodyIsMermaid]] has that rule.
    *   - A header with no body is no evidence, except for the first five letter
    *     forms. A person who types `graph G {` has the text `graph` for a moment,
    *     and auto-detect must not change the language then.
    */
  private def declaresGraphFlowchart(header: String): Boolean =
    header.startsWith("graph") && {
      val (line, below) = header.drop("graph".length).span(_ != '\n')
      val spaced        = line.dropWhile(_.isWhitespace)
      if line.isEmpty then bareGraphBodyIsMermaid(below)
      else
        val direction =
          if spaced.headOption.exists(ArrowDirections.contains) then Some(spaced.take(1))
          else if spaced.length == line.length then None // `graphx`, `graph_config`, `graph{`
          else LetterDirections.find(d => spaced.startsWith(d) && spaced.drop(d.length).headOption.forall(c => !isIdentifierChar(c)))
        direction.exists: d =>
          val rest = (spaced.drop(d.length) + below).dropWhile(_.isWhitespace)
          (FirstLetterDirections.contains(d) || rest.nonEmpty) && !rest.startsWith("{")
    }

  /** True when the text after a bare `graph` line is a Mermaid body.
    *
    * DOT puts an optional name and then `{` there: `graph\n{`, `graph\nG {`,
    * `graph\n"my graph" {`. A Mermaid statement can also start with a name and a
    * `{`: in `A{Decision} --> B` the brace makes a diamond node. The difference:
    * text follows the closed DOT body only when the file has a second graph, but
    * a Mermaid statement continues after the diamond. A body with no text, or a
    * name alone, is no evidence.
    */
  private def bareGraphBodyIsMermaid(below: String): Boolean =
    val body      = below.dropWhile(_.isWhitespace)
    val afterName = dropDotName(body).dropWhile(_.isWhitespace)
    if afterName.isEmpty then false
    else if afterName.startsWith("{") then textAfterBraceBlock(afterName).exists(_.exists(!_.isWhitespace))
    else true

  /** The text without the DOT name at its start: a quoted string or an identifier. */
  private def dropDotName(text: String): String =
    if text.startsWith("\"") then DotSourceScan.quotedStringEnd(text, 1).fold("")(text.substring)
    else text.dropWhile(c => c.isLetterOrDigit || c == '_' || c == '.')

  /** The text after the brace block that opens `text`, or `None` when the block does
    * not close. A brace in a quoted string does not count.
    */
  private def textAfterBraceBlock(text: String): Option[String] =
    @scala.annotation.tailrec
    def loop(i: Int, depth: Int): Option[String] =
      if i >= text.length then None
      else
        text.charAt(i) match
          case '"' =>
            DotSourceScan.quotedStringEnd(text, i + 1) match
              case Some(next) => loop(next, depth)
              case None       => None
          case '{'               => loop(i + 1, depth + 1)
          case '}' if depth == 1 => Some(text.substring(i + 1))
          case '}'               => loop(i + 1, depth - 1)
          case _                 => loop(i + 1, depth)
    loop(0, 0)

  /** The title that the diagram declares, when the source has one: a `title:` entry
    * in the YAML frontmatter, or a separate `title <text>` line (gantt, journey, C4,
    * timeline, xychart, ...). A flowchart gets only the frontmatter form. It has no
    * `title` keyword, so a node with the id `title` must not become a title. The scan
    * ignores inline suffix forms (`pie showData title X`) on purpose. It reads only the
    * top of the source, because a title is in the header, and this runs on each
    * keystroke while the diagram has no name.
    */
  def diagramTitle(text: String): Option[String] =
    val lines = text.linesIterator.map(_.trim).take(50).toList
    // The header scan of diagramKind skips the frontmatter, and it knows each `graph` header.
    def isFlowchart = diagramKind(text).contains("flowchart")
    def titleLine =
      Option.unless(isFlowchart)(
        lines.collectFirst { case l if l.startsWith("title ") => l.drop("title ".length).trim }
      ).flatten
    frontmatterTitleOf(lines)
      .orElse(titleLine.map(stripSurroundingQuotes).filter(_.nonEmpty))

  /** The `title:` entry of the YAML frontmatter, when the source has one.
    *
    * `mermaidAPI.getDiagramFromText` removes the frontmatter and does not give
    * its title to the diagram, so the parsed diagram has no title.
    */
  def frontmatterTitle(text: String): Option[String] =
    frontmatterTitleOf(text.linesIterator.map(_.trim).take(50).toList)

  private def frontmatterTitleOf(lines: List[String]): Option[String] =
    rawFrontmatterTitle(lines).map(titleFromYaml).filter(_.nonEmpty)

  private def rawFrontmatterTitle(lines: List[String]): Option[String] =
    lines match
      case "---" :: rest =>
        rest.takeWhile(_ != "---").collectFirst {
          case l if l.startsWith("title:") => l.drop("title:".length)
        }
      case _ => None

  private def stripSurroundingQuotes(s: String): String =
    if s.length >= 2 && s.head == '"' && s.last == '"' then s.substring(1, s.length - 1).trim else s

  // ── the front-matter title as a YAML scalar ──────────────────────────────
  //
  // Mermaid reads the front matter as YAML. In a plain YAML scalar, `: ` makes
  // a second mapping, and ` #` starts a comment. The printer and all readers
  // use the two functions below, so that a title that they print and read
  // again does not change.

  /** The punctuation that a plain title can have, after its first letter. */
  private val PlainTitlePunctuation: Set[Char] = Set('-', '_', '.', ',', '(', ')', '/', '&', '+', '?', '!', '\'')

  /** Words that YAML can read as a null or a boolean, and not as a string. */
  private val YamlKeywords: Set[String] = Set("null", "true", "false", "yes", "no", "on", "off", "y", "n")

  /** The YAML scalar for `title` in the front matter `title:` entry.
    *
    * A title that is safe as a plain scalar stays plain. A safe title starts
    * with a letter, does not end with a space, has only letters, digits,
    * spaces and some punctuation, and is not a YAML keyword. The function
    * writes all other titles as a YAML double-quoted scalar.
    */
  def titleToYaml(title: String): String =
    if isPlainTitle(title) then title else "\"" + title.flatMap(escapeYamlChar) + "\""

  private def isPlainTitle(title: String): Boolean =
    title.headOption.exists(_.isLetter) &&
      !title.last.isWhitespace &&
      title.forall(c => c.isLetterOrDigit || c == ' ' || PlainTitlePunctuation.contains(c)) &&
      !YamlKeywords.contains(title.toLowerCase)

  private def escapeYamlChar(c: Char): String = c match
    case '\\'             => "\\\\"
    case '"'              => "\\\""
    case '\n'             => "\\n"
    case '\r'             => "\\r"
    case '\t'             => "\\t"
    case c if c.isControl => "\\u%04x".format(c.toInt)
    case c                => c.toString

  /** The title in `raw`, the YAML scalar after `title:` in the front matter.
    *
    * The function removes the quotes of a double-quoted scalar and decodes its
    * escapes. It removes the quotes of a single-quoted scalar and changes `''`
    * to `'`. It removes a ` #` comment from a plain scalar, and trims it.
    */
  def titleFromYaml(raw: String): String =
    val value = raw.trim
    DoubleQuotedScalar.findPrefixMatchOf(value).map(m => unescapeYaml(m.group(1)))
      .orElse(SingleQuotedScalar.findPrefixMatchOf(value).map(_.group(1).replace("''", "'")))
      .getOrElse(PlainComment.findFirstMatchIn(value).fold(value)(m => value.take(m.start)).trim)

  private val DoubleQuotedScalar = "\"((?:[^\"\\\\]|\\\\.)*)\"".r
  private val SingleQuotedScalar = "'((?:[^']|'')*)'".r
  private val PlainComment       = "(?:^|\\s)#".r
  private val YamlEscape         = "\\\\(x[0-9A-Fa-f]{2}|u[0-9A-Fa-f]{4}|U[0-9A-Fa-f]{8}|.)".r

  private def unescapeYaml(body: String): String =
    YamlEscape.replaceAllIn(body, m => scala.util.matching.Regex.quoteReplacement(decodeYamlEscape(m.group(1))))

  /** The text of one YAML escape. `code` is the text after the backslash. The
    * function keeps an escape that it does not know as it is.
    */
  private def decodeYamlEscape(code: String): String = code match
    case "n"  => "\n"
    case "t"  => "\t"
    case "r"  => "\r"
    case "0"  => "\u0000"
    case "a"  => "\u0007"
    case "b"  => "\b"
    case "e"  => "\u001b"
    case "f"  => "\f"
    case "v"  => "\u000b"
    case "N"  => "\u0085"
    case "_"  => "\u00a0"
    case "L"  => "\u2028"
    case "P"  => "\u2029"
    case "\t" => "\t"
    case hex if hex.length > 1 =>
      val codePoint = Integer.parseInt(hex.drop(1), 16)
      if Character.isValidCodePoint(codePoint) then String(Character.toChars(codePoint)) else "\\" + hex
    case other if "\"\\/ ".contains(other) => other
    case other => "\\" + other

  private val IgnoredLinePrefixes = List(
    "%%",
    "flowchart",
    "graph",
    "subgraph",
    "classdef",
    "class ",
    "style ",
    "linkstyle",
    "click ",
    "end"
  )

  def isIgnoredLine(line: String): Boolean =
    val lower = line.toLowerCase
    IgnoredLinePrefixes.exists(prefix => isIgnoredPrefix(lower, prefix))

  // A directive keyword only ignores a line when it appears as a WHOLE word, so a node
  // or edge id that merely starts with one (e.g. `graphState[...]`, `endNode --> B`) is
  // not dropped. Prefixes already ending in a space, and the `%%` comment marker, keep
  // plain startsWith semantics.
  private def isIgnoredPrefix(lower: String, prefix: String): Boolean =
    if prefix.endsWith(" ") || prefix == "%%" then lower.startsWith(prefix)
    else lower == prefix || (lower.startsWith(prefix) && !isIdentifierChar(lower.charAt(prefix.length)))

  def isIdentifierChar(c: Char): Boolean =
    c.isLetterOrDigit || c == '_' || c == '-'

  // ── diagnosing a failed parse ────────────────────────────────────────────
  //
  // Mermaid's sequence lexer matches these as keywords case-INSENSITIVELY and
  // whole-word (`/^(?:actor\b)/i` and friends), so none of them can be a
  // participant id — but `Actors` and `MyActor` are fine. Taken from the lexer
  // rules in mermaid 11.12's sequenceDiagram chunk, then each one verified by
  // actually feeding `participant <kw>` to the parser: 29 of the 30 keywords
  // break, `as` being the sole survivor. The two-word forms (`left of`,
  // `right of`) cannot appear as an id anyway and are left out.
  private val SequenceReservedWords = Set(
    "activate", "actor", "alt", "and", "autonumber", "box", "break", "create",
    "critical", "deactivate", "destroy", "details", "else", "end", "link",
    "links", "loop", "note", "off", "opt", "option", "over", "par",
    "participant", "properties", "rect", "sequencediagram"
  )

  private val SequenceArrow = "(?:--?)(?:>>?|\\)|x)".r

  /** True when the source declares a sequence diagram (the only place the
    * reserved-word rule below applies — `Actor` is a perfectly good flowchart
    * node id). The header scan of [[diagramKind]] skips the frontmatter. */
  private def isSequenceDiagram(source: String): Boolean =
    diagramKind(source).contains("sequence")

  /** A human explanation for a Mermaid parse failure, when the source contains
    * something we recognise as a known trap. Runs only AFTER mermaid has
    * rejected the text, so it can never block a diagram mermaid would accept —
    * and returns `None` whenever it has nothing useful to add.
    *
    * The case it exists for: mermaid's own message names neither the offending
    * word nor the line that introduced it. A participant called `Actor` reports
    *
    * {{{
    * Parse error on line 12: ...Caller->>Actor: create/load s
    * Expecting '+', '-', 'ACTOR', got 'participant_actor'
    * }}}
    *
    * — line 12 being the first message that mentions it, while the declaration
    * on line 4 parsed happily.
    */
  def explainParseFailure(source: String): Option[String] =
    Option.when(isSequenceDiagram(source))(reservedParticipant(source)).flatten

  private def reservedParticipant(source: String): Option[String] =
    val declaration = "^\\s*(?:participant|actor)\\s+([^\\s:]+)".r
    val hit = source.linesIterator.zipWithIndex
      .flatMap { (line, idx) =>
        val fromDecl = declaration.findFirstMatchIn(line).map(_.group(1))
        // an undeclared participant is auto-created by its first message, so
        // check both sides of an arrow too
        val fromMessage = Option.when(fromDecl.isEmpty) {
          SequenceArrow.findFirstMatchIn(line).flatMap { m =>
            val lhs = line.substring(0, m.start).trim
            val rhs = line.substring(m.end).takeWhile(_ != ':').trim
            Vector(lhs, rhs).map(stripActivationMark).find(isReserved)
          }
        }.flatten
        fromDecl.filter(isReserved).orElse(fromMessage).map(_ -> (idx + 1))
      }
      .nextOption()
    hit.map { (word, line) =>
      s"'$word' is a reserved word in Mermaid sequence diagrams (line $line), so it cannot be used " +
        s"as a participant id — the name is matched whole-word and ignoring case. Rename the id and " +
        s"keep the label with `as`, e.g. `participant Agent as $word`. " +
        s"(A longer name containing it, like '${word}s', is fine.)"
    }

  /** `A->>+B` / `A->>-B`: the +/- activation marks are not part of the name. */
  private def stripActivationMark(s: String): String =
    s.dropWhile(c => c == '+' || c == '-').trim

  private def isReserved(candidate: String): Boolean =
    candidate.nonEmpty && SequenceReservedWords.contains(candidate.toLowerCase)

  /** Strip wrapping quotes and decode the `#quot;` escape used by the Mermaid writer. */
  def normalizeLabel(raw: String): String =
    val trimmed = raw.trim
    val unquoted =
      if trimmed.length >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")
      then trimmed.substring(1, trimmed.length - 1)
      else trimmed
    decodeQuotes(unquoted).trim

  /** The Mermaid entity of a quote. A quoted Mermaid label cannot hold a `"`. */
  private val QuoteEntity = "#quot;"

  /** The label with each `"` as `#quot;`. The Mermaid writer writes a quoted label in this form. */
  def encodeQuotes(label: String): String = label.replace("\"", QuoteEntity)

  /** The text with each `#quot;` as `"`. This is the inverse of [[encodeQuotes]]. */
  def decodeQuotes(text: String): String = text.replace(QuoteEntity, "\"")

  /** The internal code that Mermaid puts in place of `#name;` and `#123;` before it parses (`encodeEntities`). */
  private val EntityCode = "ﬂ°°?(\\w+)¶ß".r

  /** A text from the Mermaid parser (a node label, an arrow label or a subgraph title) in the form that the app's
    * own parser gives.
    *
    * Real Mermaid changes each `#name;` and `#123;` of the source into an internal code before it parses. The parser
    * results keep that code. This function gives back the source form of each code, and then it decodes `#quot;`,
    * as [[normalizeLabel]] does.
    */
  def decodeEntityCodes(text: String): String =
    // The group is `\w+`, thus the replacement has no `$` and no `\`
    decodeQuotes(EntityCode.replaceAllIn(text, m => s"#${m.group(1)};"))
