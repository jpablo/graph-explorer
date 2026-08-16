package org.jpablo.graphexplorer.viewer.backends.mermaid

/** Shared source-text scanning helpers for the Mermaid*Fallback parsers, so the
  * directive-skip rules and label normalization cannot drift between fallbacks
  * (the whole-word prefix fix once had to be applied to two identical copies).
  * `private[backends]` (not `[mermaid]`) so DiagramFormat.detect can delegate to
  * [[looksLikeMermaid]] — the grammar catalogue belongs in this package.
  */
private[backends] object MermaidSourceScan:

  // Display name by lowercased header prefix, for every Mermaid diagram type
  // bundled with mermaid 11 (plus the flowchart `graph <dir>` forms). ONE table
  // for both detection ([[looksLikeMermaid]]) and naming ([[diagramKind]]), so a
  // type we detect always has a name and vice versa.
  private val KindByPrefix: List[(String, String)] = List(
    "flowchart"          -> "flowchart",
    "graph td"           -> "flowchart",
    "graph tb"           -> "flowchart",
    "graph bt"           -> "flowchart",
    "graph lr"           -> "flowchart",
    "graph rl"           -> "flowchart",
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
      KindByPrefix.collectFirst { case (prefix, kind) if declares(header, prefix) => kind }

  /** The document from its header line onward, trimmed per line and lowercased;
    * `None` when it has no header at all (frontmatter and comments only).
    *
    * From the header ONWARD rather than the header LINE, so the brace test in
    * [[declares]] can see a `{` that DOT put on the following line.
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

  /** Whether `header` opens with `prefix` AS THIS LANGUAGE'S KEYWORD.
    *
    * Plain `startsWith` everywhere except `graph <dir>`, the one point where the
    * two vocabularies collide: `graph LR { a -- b }` is an UNDIRECTED DOT GRAPH
    * NAMED LR, not a left-to-right flowchart. Two things tell them apart and
    * both are needed — DOT's graph name is an identifier that a direction may
    * merely prefix (`graph LRX`), and DOT opens a brace where Mermaid's header
    * simply ends (a same-line Mermaid statement follows a `;`, never a `{`).
    */
  private def declares(header: String, prefix: String): Boolean =
    header.startsWith(prefix) && (!prefix.startsWith("graph ") || {
      val rest = header.drop(prefix.length)
      rest.headOption.forall(c => !isIdentifierChar(c)) &&
      !rest.dropWhile(_.isWhitespace).startsWith("{")
    })

  /** The diagram's declared title, when the source carries one: a YAML-frontmatter
    * `title:` entry, or a standalone `title <text>` line (gantt, journey, C4, timeline,
    * xychart, ...). Flowcharts get the frontmatter form only — they have no `title`
    * keyword, so a node id happening to be `title` must not be read as one. Inline
    * suffix forms (`pie showData title X`) are intentionally not chased. Only the top
    * of the document is scanned: titles live in the header, and this runs per keystroke
    * while a project is still unnamed.
    */
  def diagramTitle(text: String): Option[String] =
    val lines = text.linesIterator.map(_.trim).take(50).toList
    val fromFrontmatter = lines match
      case "---" :: rest =>
        rest.takeWhile(_ != "---").collectFirst {
          case l if l.startsWith("title:") => l.drop("title:".length).trim
        }
      case _ => None
    def firstContent = lines.find(l => l.nonEmpty && !l.startsWith("%%") && l != "---")
    def isFlowchart  = firstContent.exists(l => l.startsWith("flowchart") || l.toLowerCase.startsWith("graph "))
    fromFrontmatter
      .orElse(
        Option.unless(isFlowchart)(
          lines.collectFirst { case l if l.startsWith("title ") => l.drop("title ".length).trim }
        ).flatten
      )
      .map(stripSurroundingQuotes)
      .filter(_.nonEmpty)

  private def stripSurroundingQuotes(s: String): String =
    if s.length >= 2 && s.head == '"' && s.last == '"' then s.substring(1, s.length - 1).trim else s

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
    * node id). */
  private def isSequenceDiagram(source: String): Boolean =
    source.linesIterator
      .map(_.trim)
      .find(l => l.nonEmpty && !l.startsWith("%%") && l != "---")
      .exists(_.toLowerCase.startsWith("sequencediagram"))

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
    unquoted.replace("#quot;", "\"").trim
