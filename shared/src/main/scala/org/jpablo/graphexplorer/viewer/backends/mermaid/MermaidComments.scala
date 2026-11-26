package org.jpablo.graphexplorer.viewer.backends.mermaid

import scala.util.Try

/** The `%%` comments of a Mermaid flowchart, and the place of each comment.
  *
  * The graph does not keep the order of the statements, and the printer writes
  * them in its own order. Thus a comment cannot keep its line number. It keeps
  * its anchor instead: the statement that comes after it in the source.
  *
  * The reader makes an outline of the source: the runs of comment lines and the
  * statements that a comment can attach to, in the order of the source. The
  * graph keeps the outline, as JSON text, in the graph attribute
  * [[MermaidAttrKeys.MermaidCommentsAttr]].
  *
  * The rule for the anchor of a run of comments:
  *
  *   - The anchor is the first statement after the run, in the same scope (the
  *     top level or one subgraph), that the printed text has. A statement is the
  *     init directive, the header, a node, an arrow, a `subgraph` line or an
  *     `end` line. The `end` line of a subgraph is the last statement of its
  *     scope.
  *   - The printer writes the run on the line before its anchor, with the indent
  *     of the anchor.
  *   - When an edit removes the anchor, the next statement of the scope is the
  *     anchor. When an edit removes the scope (the subgraph), the run moves to the
  *     scope that contains it. A run with no statement after it goes to the end
  *     of the text.
  *
  * Thus the printer does not drop a comment.
  */
object MermaidComments:

  /** One item of the outline. */
  enum Item derives CanEqual:
    /** A run of comment lines. Each line starts with `%%`, with no indent. */
    case Comments(lines: List[String])
    /** The init directive `%%{...}%%`. */
    case Directive
    /** The `flowchart` or `graph` line. */
    case Header
    /** A statement that declares a node with no arrow. */
    case Node(id: String)
    /** An arrow, as the graph numbers it (see [[flowchartArrowSeqs]]). */
    case Edge(start: String, end: String, seq: Int)
    /** The `subgraph` line of a subgraph. */
    case Subgraph(id: String)
    /** The `end` line of a subgraph. */
    case End(id: String)

  /** The outline of `text`, as JSON text. `None` when the text has no comment. */
  def outlineOf(text: String): Option[String] =
    val items = scan(text)
    Option.when(items.exists(_.isInstanceOf[Item.Comments]))(render(items))

  /** The items of an outline. `json` is the text of [[outlineOf]]. Bad JSON gives no item. */
  def parse(json: String): Vector[Item] =
    Try(ujson.read(json).arr.toVector.flatMap(itemOf)).getOrElse(Vector.empty)

  /** The comment lines of each anchor. `printed` is true for each statement
    * that the printed text has. The key `None` is the end of the text.
    *
    * The lines of two runs with the same anchor come in the order of the source.
    */
  def placed(items: Vector[Item], printed: Item => Boolean): Map[Option[Item], Vector[String]] =
    val runs = items.zipWithIndex.collect:
      case (Item.Comments(lines), i) => anchorOf(items, i, printed) -> lines
    runs.foldLeft(Map.empty[Option[Item], Vector[String]]):
      case (acc, (anchor, lines)) => acc.updated(anchor, acc.getOrElse(anchor, Vector.empty) ++ lines)

  /** The first statement after the run at `index`, in its scope, that the
    * printed text has. See the rule in the description of [[MermaidComments]].
    */
  private def anchorOf(items: Vector[Item], index: Int, printed: Item => Boolean): Option[Item] =
    // The depth of the subgraphs that the scan entered and that the printed text does not have.
    var depth  = 0
    var anchor = Option.empty[Item]
    var j      = index + 1
    while anchor.isEmpty && j < items.length do
      items(j) match
        case Item.Comments(_) => ()
        case item @ Item.Subgraph(_) =>
          if depth == 0 && printed(item) then anchor = Some(item) else depth += 1
        case item @ Item.End(_) =>
          // An `end` at depth 0 closes the scope of the run. When the printed
          // text does not have it, the scan continues in the scope that contains it.
          if depth == 0 then { if printed(item) then anchor = Some(item) }
          else depth -= 1
        case item => if depth == 0 && printed(item) then anchor = Some(item)
      j += 1
    anchor

  // ------------------------------------------------------------- the scan

  private val Keywords = Set("style", "classDef", "class", "linkStyle", "click", "direction")
  private val AccessibleLine = raw"^acc(?:Title|Descr)\s*[:{].*$$".r

  /** The outline of a flowchart source. The scan reads Mermaid one line at a
    * time, as [[RoundTripLoss]] does. It does not check the syntax.
    */
  private[mermaid] def scan(text: String): Vector[Item] =
    val lines   = text.linesIterator.map(_.trim).toVector
    val body    = lines.drop(frontmatterEnd(lines))
    val items   = Vector.newBuilder[Item]
    val pending = List.newBuilder[String]
    var hasPending   = false
    var stack        = List.empty[String]
    var sawHeader    = false
    var inDirective  = false
    var inAccDescr   = false
    val counts       = scala.collection.mutable.Map[(String, String), Int]().withDefaultValue(0)

    def flush(): Unit =
      if hasPending then
        items += Item.Comments(pending.result())
        pending.clear()
        hasPending = false

    def emit(item: Item): Unit =
      flush()
      items += item

    def statement(stmt: String): Unit =
      val word = stmt.takeWhile(c => !c.isWhitespace && c != ';')
      if !sawHeader then
        sawHeader = true
        emit(Item.Header)
      else if word == "subgraph" then
        val id = MermaidComments.id(stmt.drop("subgraph".length).trim)
        stack = id :: stack
        emit(Item.Subgraph(id))
      else if stmt == "end" then
        stack match
          case id :: rest =>
            stack = rest
            emit(Item.End(id))
          case Nil => ()
      else if Keywords.contains(word) || AccessibleLine.matches(stmt) then ()
      else
        chain(stmt) match
          case Nil => ()
          case nodes :: Nil => nodes.foreach(id => emit(Item.Node(id)))
          case groups =>
            groups.sliding(2).foreach:
              case List(starts, ends) =>
                for start <- starts; end <- ends do
                  counts((start, end)) += 1
                  emit(Item.Edge(start, end, counts((start, end))))
              case _ => ()

    body.foreach: line =>
      if inDirective then inDirective = !line.contains("}%%")
      else if inAccDescr then inAccDescr = !line.contains("}")
      else if line.startsWith("%%{") then
        emit(Item.Directive)
        inDirective = !line.drop(3).contains("}%%")
      else if line.startsWith("%%") then
        pending += line
        hasPending = true
      else if line.nonEmpty then
        if line.startsWith("accDescr") && line.contains("{") then
          inAccDescr = !line.drop(line.indexOf('{') + 1).contains("}")
        statements(line).foreach(statement)
    flush()
    items.result()

  /** The index of the first line after the front matter. */
  private def frontmatterEnd(lines: Vector[String]): Int =
    if !lines.headOption.contains("---") then 0
    else
      val closing = lines.indexWhere(_ == "---", 1)
      if closing < 0 then 0 else closing + 1

  /** The statements of a line: the parts between the `;` that are not inside a
    * quoted string. Each part is trimmed.
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

  /** The id at the start of `s`. A `-` is part of an id only before a letter or
    * a digit, because `--` starts a link.
    */
  private def id(s: String): String =
    val out = StringBuilder()
    var i   = 0
    var go  = true
    while go && i < s.length do
      val c = s.charAt(i)
      val inId =
        c.isLetterOrDigit || c == '_' ||
          (c == '-' && out.nonEmpty && i + 1 < s.length && (s.charAt(i + 1).isLetterOrDigit || s.charAt(i + 1) == '_'))
      if inId then out += c
      go = inId
      i += 1
    out.toString

  // A link with no text, then an optional `|text|`.
  private val PlainLink = raw"^\s*(?:[<xo]?(?:-{2,}|={2,}|-\.+-)[>xo]?|~~~)\s*(?:\|[^|]*\|)?\s*".r
  // A link with its text inside it: `-- text -->`, `== text ==>`, `-. text .->`.
  private val TextLink = raw"^\s*[<xo]?(?:--|==|-\.)\s+.*?\s+(?:-{2,}|={2,}|\.+-)[>xo]?\s*".r

  /** The node groups of a statement: `a & b --> c --> d` gives
    * `List(List(a, b), List(c), List(d))`. A statement with no link gives one
    * group.
    */
  private def chain(stmt: String): List[List[String]] =
    def nodes(s: String): (List[String], String) =
      val first = id(s)
      if first.isEmpty then (Nil, s)
      else
        val rest = skipNodeTail(s.drop(first.length))
        val more = rest.dropWhile(_.isWhitespace)
        if more.startsWith("&") then
          val (others, after) = nodes(more.drop(1).dropWhile(_.isWhitespace))
          (first :: others, after)
        else (List(first), rest)

    def links(rest: String): List[List[String]] =
      TextLink.findPrefixMatchOf(rest).orElse(PlainLink.findPrefixMatchOf(rest)) match
        case Some(m) =>
          nodes(rest.drop(m.end)) match
            case (Nil, _)    => Nil
            case (ids, more) => ids :: links(more)
        case None => Nil

    nodes(stmt) match
      case (Nil, _)      => Nil
      case (first, rest) => first :: links(rest)

  /** The text after the id of a node: its shape and label, and a `:::class`. */
  private def skipNodeTail(s: String): String =
    val afterShape =
      if s.startsWith("@{") then skipBalanced(s, 1)
      else if s.headOption.exists(c => c == '[' || c == '(' || c == '{') then skipBalanced(s, 0)
      // `>text]` is the asymmetric shape.
      else if s.startsWith(">") && !s.startsWith(">]") then
        val close = s.indexOf(']')
        if close < 0 then "" else s.drop(close + 1)
      else s
    if afterShape.startsWith(":::") then afterShape.drop(3).dropWhile(c => !c.isWhitespace && c != '&')
    else afterShape

  /** The text after the brackets that open at `from`. A bracket in a quoted string is text. */
  private def skipBalanced(s: String, from: Int): String =
    var depth  = 0
    var quoted = false
    var i      = from
    var done   = false
    while !done && i < s.length do
      val c = s.charAt(i)
      if c == '"' then quoted = !quoted
      else if !quoted then
        if c == '[' || c == '(' || c == '{' then depth += 1
        else if c == ']' || c == ')' || c == '}' then
          depth -= 1
          done = depth == 0
      i += 1
    s.drop(i)

  // ------------------------------------------------------------- the JSON

  private def render(items: Vector[Item]): String =
    ujson.write(ujson.Arr.from(items.map(jsonOf)))

  private def jsonOf(item: Item): ujson.Value =
    item match
      case Item.Comments(lines)        => ujson.Arr.from("comments" +: lines)
      case Item.Directive              => ujson.Arr("directive")
      case Item.Header                 => ujson.Arr("header")
      case Item.Node(id)               => ujson.Arr("node", id)
      case Item.Edge(start, end, seq)  => ujson.Arr("edge", start, end, seq)
      case Item.Subgraph(id)           => ujson.Arr("subgraph", id)
      case Item.End(id)                => ujson.Arr("end", id)

  private def itemOf(value: ujson.Value): Option[Item] =
    value.arrOpt.map(_.toList).flatMap:
      case ujson.Str("comments") :: lines => Some(Item.Comments(lines.flatMap(_.strOpt)))
      case List(ujson.Str("directive"))   => Some(Item.Directive)
      case List(ujson.Str("header"))      => Some(Item.Header)
      case List(ujson.Str("node"), ujson.Str(id)) => Some(Item.Node(id))
      case List(ujson.Str("edge"), ujson.Str(start), ujson.Str(end), ujson.Num(seq)) =>
        Some(Item.Edge(start, end, seq.toInt))
      case List(ujson.Str("subgraph"), ujson.Str(id)) => Some(Item.Subgraph(id))
      case List(ujson.Str("end"), ujson.Str(id))      => Some(Item.End(id))
      case _                                          => None
