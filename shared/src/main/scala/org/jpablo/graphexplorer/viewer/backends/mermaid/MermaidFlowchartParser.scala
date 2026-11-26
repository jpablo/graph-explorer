package org.jpablo.graphexplorer.viewer.backends.mermaid

import scala.collection.mutable

/** A deterministic, platform-independent reader for Mermaid flowcharts.
  *
  * Mermaid's JavaScript parser needs a browser-like runtime. The graph model does not: the CLI needs only the declarations, links and
  * subgraph memberships. This reader covers the flowchart grammar that Graph Explorer can model and edit. Other Mermaid diagram kinds
  * remain render-only.
  */
object MermaidFlowchartParser:

  // Real Mermaid matches its keywords and directions with case (Mermaid 11.12): `Class --> b` is an arrow, and
  // `Style a fill:#f00` and `flowchart lr` are errors. Thus these patterns also match with case.
  // The header that the Mermaid 11.12 lexer reads: the keyword alone, a letter direction after a space, or an arrow
  // direction with or without a space. Only a header with a direction can have a `;`.
  private val HeaderLine    = raw"^(?:flowchart|graph)(?:(?:\s+(TD|TB|BT|LR|RL|BR|v)|\s*([<>^]))\s*;?|\s*)$$".r
  private val DirectionLine = raw"^direction\s+(TD|TB|BT|LR|RL)\s*;?$$".r
  private val SubgraphLine  = raw"^subgraph\s+([A-Za-z0-9_][A-Za-z0-9_-]*)(?:\s*\[(.*)\])?\s*;?$$".r
  private val StyleLine     = raw"^style\s+([A-Za-z0-9_][A-Za-z0-9_-]*)\s+(.+?)\s*;?$$".r
  private val ClassDefLine  = raw"^classDef\s+([^\s]+)\s+(.+?)\s*;?$$".r
  private val ClassLine     = raw"^class\s+([^\s]+)\s+(.+?)\s*;?$$".r
  private val LinkStyleLine = raw"^linkStyle\s+(default|\d+)\s+(.+?)\s*;?$$".r
  // `accTitle` and `accDescr` are text for screen readers. The graph does not keep them. With case, as Mermaid.
  private val AccessibleLine = raw"^acc(?:Title|Descr)\s*:.*$$".r
  private val AccDescrBlock  = raw"^accDescr\s*\{(.*)$$".r
  // A node with shape data (Mermaid 11): `id@{ shape: rect, label: "Text" }` or `id@{ img: "...", w: 60, h: 60 }`.
  private val ShapeDataLine = raw"^([A-Za-z0-9_][A-Za-z0-9_-]*)\s*@\{(.*)\}\s*;?$$".r
  // The link of a node (`click id href "url"`, or `click id "url"`), with a tooltip and a target. Mermaid reads the
  // other `click` lines as a callback, which the graph does not keep.
  private val LinkClickLine =
    raw"""^click\s+([^\s,"]+)\s+(?:href\s+)?"([^"]*)"(?:\s+"([^"]*)")?(?:\s+(_self|_blank|_parent|_top))?\s*;?$$""".r
  private val EdgeLine =
    raw"^([^\s]+)\s+(<-->|<-\.->|<==>|-->|-\.->|==>|---|-\.-|===)(?:\|([^|]*)\|)?\s+([^\s]+)\s*;?$$".r

  /** Bracket pairs in match order, from the most specific to the most general. */
  private val Brackets: List[(String, String, String)] = List(
    ("(((", ")))", "doublecircle"),
    ("((", "))", "circle"),
    ("([", "])", "stadium"),
    ("[[", "]]", "subroutine"),
    ("[(", ")]", "cylinder"),
    ("{{", "}}", "hexagon"),
    ("[/", "/]", "parallelogram"),
    ("[/", "\\]", "trapezoid"),
    ("[\\", "/]", "trapezoid-alt"),
    ("{", "}", "diamond"),
    ("[", "]", "square"),
    ("(", ")", "round")
  )

  def parse(text: String): Either[String, MermaidGraph] =
    val rawLines               = text.linesIterator.toVector
    val (title, firstBodyLine) = frontmatter(rawLines)

    val vertices         = mutable.LinkedHashMap[String, MermaidVertex]()
    val edges            = mutable.ListBuffer[MermaidEdge]()
    val subgraphsInOrder = mutable.ListBuffer[String]()
    val subgraphTitles   = mutable.Map[String, Option[String]]()
    val subgraphMembers  = mutable.Map[String, mutable.LinkedHashSet[String]]()
    val subgraphDirs     = mutable.Map[String, String]()
    val stack            = mutable.Stack[String]()
    val nodeStyles       = mutable.Map[String, List[String]]()
    val classAssignments = mutable.Map[String, List[String]]()
    val classDefs        = mutable.LinkedHashMap[String, MermaidClassDef]()
    val linkStyles       = mutable.Map[Int, (List[String], Option[String])]()
    val links            = mutable.Map[String, (String, Option[String], Option[String])]()

    var direction: Option[String]              = None
    var defaultEdgeStyle: List[String]         = Nil
    var defaultEdgeInterpolate: Option[String] = None
    var sawHeader                              = false
    var inAccDescrBlock                        = false
    var inDirective                            = false
    var failure: Option[String]                = None

    def registerVertex(vertex: MermaidVertex): Unit =
      vertices.get(vertex.id) match
        case None => vertices(vertex.id) = vertex
        case Some(existing) if existing.text == existing.id && vertex.text != vertex.id =>
          vertices(vertex.id) = vertex.copy(
            styles = (existing.styles ++ vertex.styles).distinct,
            classes = (existing.classes ++ vertex.classes).distinct
          )
        case _ => ()

    def addMember(id: String): Unit =
      stack.headOption.foreach(parent => subgraphMembers(parent) += id)

    def splitCss(body: String): (List[String], Option[String]) =
      val declarations          = body.stripSuffix(";").split(",").iterator.map(_.trim).filter(_.nonEmpty).toList
      val (interpolate, styles) = declarations.partition(_.toLowerCase.startsWith("interpolate:"))
      (styles, interpolate.headOption.map(_.split(":", 2)(1).trim))

    rawLines.drop(firstBodyLine).zipWithIndex.foreach { case (rawLine, relativeIndex) =>
      if failure.isEmpty then
        val lineNumber = firstBodyLine + relativeIndex + 1
        val line       = rawLine.trim
        line match
          case ""                         => ()
          // A `%%{...}%%` directive can have more than one line. MermaidInitDirective reads the init directive.
          case _ if inDirective           => inDirective = !line.contains("}%%")
          case _ if line.startsWith("%%{") => inDirective = !line.contains("}%%")
          case _ if line.startsWith("%%") => ()
          // A multi-line `accDescr { ... }` block continues to the line with `}`.
          case _ if inAccDescrBlock       => inAccDescrBlock = !line.contains("}")
          case HeaderLine(letters, arrow) if !sawHeader =>
            direction = Some(headerDirection(Option(letters), Option(arrow)))
            sawHeader = true
          case HeaderLine(_, _) =>
            failure = Some(s"could not parse the Mermaid flowchart: duplicate header at line $lineNumber")
          case _ if !sawHeader =>
            failure = Some(s"could not parse the Mermaid flowchart: expected a flowchart header at line $lineNumber")
          // The direction of a subgraph. The graph keeps it on the group, and the printer writes it back.
          case DirectionLine(dir) => stack.headOption.foreach(id => subgraphDirs(id) = dir)
          case AccDescrBlock(rest) => inAccDescrBlock = !rest.contains("}")
          case "end" | "end;" =>
            if stack.nonEmpty then stack.pop()
            else failure = Some(s"could not parse the Mermaid flowchart: unmatched 'end' at line $lineNumber")
          case SubgraphLine(id, rawTitle) =>
            if subgraphTitles.contains(id) then
              failure = Some(s"could not parse the Mermaid flowchart: duplicate subgraph '$id' at line $lineNumber")
            else
              addMember(id)
              stack.push(id)
              subgraphsInOrder += id
              subgraphTitles(id) = Option(rawTitle).map(MermaidSourceScan.normalizeLabel).filter(_.nonEmpty)
              subgraphMembers.getOrElseUpdate(id, mutable.LinkedHashSet())
          case ClassDefLine(rawNames, body) =>
            val declarations = body.stripSuffix(";").split(",").iterator.map(_.trim).filter(_.contains(":")).toList
            rawNames.split(",").iterator.map(_.trim).filter(_.nonEmpty).foreach: name =>
              val previous = classDefs.getOrElse(name, MermaidClassDef())
              classDefs(name) = previous.copy(styles = (previous.styles ++ declarations).distinct)
          case ClassLine(rawIds, rawClasses) =>
            val classes = splitNames(rawClasses)
            splitNames(rawIds).foreach: id =>
              classAssignments(id) = (classAssignments.getOrElse(id, Nil) ++ classes).distinct
          case LinkStyleLine(target, body) =>
            val (styles, interpolate) = splitCss(body)
            if target == "default" then
              defaultEdgeStyle = styles
              defaultEdgeInterpolate = interpolate
            else linkStyles(target.toInt) = (styles, interpolate)
          case StyleLine(id, body) =>
            nodeStyles(id) = body.stripSuffix(";").split(",").iterator.map(_.trim).filter(_.nonEmpty).toList
          case ShapeDataLine(id, body) =>
            val vertex = shapeDataVertex(id, shapeData(body))
            registerVertex(vertex)
            addMember(vertex.id)
          case EdgeLine(rawSource, arrow, rawLabel, rawTarget) =>
            (parseNodeRef(rawSource), parseNodeRef(rawTarget)) match
              case (Some(source), Some(target)) =>
                registerVertex(source)
                registerVertex(target)
                addMember(source.id)
                addMember(target.id)
                edges += MermaidEdge(
                  start = source.id,
                  end = target.id,
                  edgeType = Some(edgeType(arrow)),
                  text = Option(rawLabel).map(MermaidSourceScan.normalizeLabel).filter(_.nonEmpty),
                  stroke = edgeStroke(arrow)
                )
              case _ =>
                failure = Some(s"could not parse the Mermaid flowchart: invalid link endpoint at line $lineNumber")
          case LinkClickLine(id, url, tooltip, target) =>
            links(id) = (url.trim, Option(tooltip).filter(_.nonEmpty), Option(target))
          case other if ignoredDirective(other) => ()
          case other =>
            parseNodeRef(other.stripSuffix(";")).filter(v => v.id == nodeIdPrefix(other.stripSuffix(";"))) match
              case Some(vertex) =>
                registerVertex(vertex)
                addMember(vertex.id)
              case None =>
                failure = Some(s"could not parse the Mermaid flowchart: unsupported or invalid statement at line $lineNumber: $other")
    }

    failure match
      case Some(message)      => Left(message)
      case None if !sawHeader => Left("could not parse the Mermaid flowchart: no flowchart header")
      case None if stack.nonEmpty =>
        Left(s"could not parse the Mermaid flowchart: subgraph '${stack.head}' has no matching 'end'")
      case None =>
        val edgeList = edges.toList.zipWithIndex.map { case (edge, index) =>
          linkStyles.get(index) match
            case Some((styles, interpolate)) => edge.copy(styles = styles, interpolate = interpolate)
            case None                        => edge
        }

        // Mermaid creates a vertex entry for a styled group. Preserve that convention so
        // toViewerGraph can move its styles onto the group and discard the phantom node.
        nodeStyles.keys.foreach: id =>
          if !vertices.contains(id) then vertices(id) = MermaidVertex(id = id, text = id)

        // As `FlowDB.setLink`, a link goes only to a node that exists.
        val vertexMap = vertices.iterator.map { case (id, vertex) =>
          val link = links.get(id).filter(_._1.nonEmpty)
          id -> vertex.copy(
            styles = nodeStyles.getOrElse(id, vertex.styles),
            classes = (vertex.classes ++ classAssignments.getOrElse(id, Nil)).distinct,
            link = link.map(_._1).orElse(vertex.link),
            tooltip = link.flatMap(_._2).orElse(vertex.tooltip),
            linkTarget = link.flatMap(_._3).orElse(vertex.linkTarget)
          )
        }.toMap

        Right(
          MermaidGraph(
            vertices = vertexMap,
            edges = edgeList,
            subgraphs = subgraphsInOrder.toList.map: id =>
              MermaidSubgraph(
                id = id,
                title = subgraphTitles(id),
                nodes = subgraphMembers(id).toList,
                classes = classAssignments.getOrElse(id, Nil),
                direction = subgraphDirs.get(id)
              ),
            direction = direction,
            title = title,
            classDefs = classDefs.toMap,
            defaultEdgeStyle = defaultEdgeStyle,
            defaultEdgeInterpolate = defaultEdgeInterpolate,
            initConfig = MermaidInitDirective.configOf(text),
            comments = MermaidComments.outlineOf(text)
          )
        )

  private def frontmatter(lines: Vector[String]): (Option[String], Int) =
    if !lines.headOption.exists(_.trim == "---") then (None, 0)
    else
      val closing = lines.indexWhere(_.trim == "---", from = 1)
      if closing < 0 then (None, 0)
      else
        val title = lines.slice(1, closing).iterator
          .map(_.trim)
          .collectFirst { case line if line.startsWith("title:") => line.drop("title:".length).trim }
          .map(MermaidSourceScan.titleFromYaml)
          .filter(_.nonEmpty)
        (title, closing + 1)

  /** The layout direction of a header, as `getDirection()` of real Mermaid 11.12 gives it.
    *
    * Mermaid changes `>` to LR, `<` to RL, `^` to BT and `v` to TB, and it gives TB to a header with no direction. It
    * keeps BR. Mermaid also changes TD to TB, but this reader keeps the letters of the text, as it did before.
    */
  private def headerDirection(letters: Option[String], arrow: Option[String]): String =
    letters.orElse(arrow).fold("TB")(dir => DirectionAliases.getOrElse(dir, dir))

  private val DirectionAliases = Map("v" -> "TB", ">" -> "LR", "<" -> "RL", "^" -> "BT")

  private def splitNames(value: String): List[String] =
    value.stripSuffix(";").split("[\\s,]+").iterator.map(_.trim).filter(_.nonEmpty).toList

  // With case, as Mermaid. The earlier test lowercased the line and then looked for `accTitle:`, thus it never
  // matched an accessibility line.
  private def ignoredDirective(line: String): Boolean =
    line.startsWith("click ") || AccessibleLine.matches(line)

  private def edgeType(arrow: String): String =
    if arrow.startsWith("<") then "double_arrow_point"
    else if !arrow.endsWith(">") then "arrow_open"
    else "arrow_point"

  private def edgeStroke(arrow: String): Option[String] = arrow match
    case "-.->" | "<-.->" | "-.-" => Some("dotted")
    case "==>" | "<==>" | "==="   => Some("thick")
    case _                        => None

  /** The keys and the values of the shape data `body` (the text between `@{` and `}`). The data is a YAML map on one
    * line: `key: value` pairs with `,` between them. A value in quotes can have a `,` or a `:`. Mermaid ends a string
    * at the next quote, so the text has `#quot;` for a quote in a value.
    */
  private def shapeData(body: String): Map[String, String] =
    val pairs   = mutable.ListBuffer[String]()
    val current = StringBuilder()
    var quote   = Option.empty[Char]
    body.foreach: c =>
      quote match
        case Some(q) =>
          current += c
          if c == q then quote = None
        case None if c == '"' || c == '\'' =>
          current += c
          quote = Some(c)
        case None if c == ',' =>
          pairs += current.toString
          current.clear()
        case None => current += c
    pairs += current.toString
    pairs.iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap: pair =>
        val colon = pair.indexOf(':')
        Option.when(colon > 0)(pair.substring(0, colon).trim -> unquote(pair.substring(colon + 1).trim))
      .toMap

  private def unquote(value: String): String =
    val bare =
      if value.length >= 2 && (value.head == '"' || value.head == '\'') && value.last == value.head then
        value.substring(1, value.length - 1)
      else value
    MermaidSourceScan.decodeQuotes(bare)

  /** The vertex of a node with shape data, as Mermaid 11 (`FlowDB.addVertex`) makes it. An image with no label has
    * the text "", and a node with no label has its id as its text.
    */
  private def shapeDataVertex(id: String, data: Map[String, String]): MermaidVertex =
    val img   = data.get("img").filter(_.trim.nonEmpty)
    val label = data.get("label").filter(_.trim.nonEmpty)
    MermaidVertex(
      id = id,
      text = label.getOrElse(if img.isDefined then "" else id),
      shape = data.get("shape").filter(_.nonEmpty),
      img = img,
      assetWidth = data.get("w").flatMap(_.toDoubleOption),
      assetHeight = data.get("h").flatMap(_.toDoubleOption)
    )

  private def nodeIdPrefix(value: String): String =
    value.takeWhile(MermaidSourceScan.isIdentifierChar)

  /** Parse an id, an optional shape/label, and optional `:::class` suffix. */
  private def parseNodeRef(raw: String): Option[MermaidVertex] =
    val trimmed = raw.trim
    val classAt = trimmed.indexOf(":::")
    val (base, classes) =
      if classAt < 0 then (trimmed, Nil)
      else (trimmed.substring(0, classAt), splitNames(trimmed.substring(classAt + 3)))

    val id = nodeIdPrefix(base)
    if id.isEmpty then None
    else
      val rest = base.drop(id.length).trim
      if rest.isEmpty then Some(MermaidVertex(id = id, text = id, classes = classes))
      else
        Brackets.collectFirst {
          case (open, close, shape)
              if rest.startsWith(open) && rest.endsWith(close) && rest.length >= open.length + close.length =>
            MermaidVertex(
              id = id,
              text = MermaidSourceScan.normalizeLabel(rest.substring(open.length, rest.length - close.length)),
              classes = classes,
              shape = Some(shape)
            )
        }
