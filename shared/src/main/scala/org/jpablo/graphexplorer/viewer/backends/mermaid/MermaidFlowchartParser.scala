package org.jpablo.graphexplorer.viewer.backends.mermaid

import scala.collection.mutable

/** A deterministic, platform-independent reader for Mermaid flowcharts.
  *
  * Mermaid's JavaScript parser needs a browser-like runtime. The graph model does not: the CLI needs only the declarations, links and
  * subgraph memberships. This reader covers the flowchart grammar that Graph Explorer can model and edit. Other Mermaid diagram kinds
  * remain render-only.
  */
object MermaidFlowchartParser:

  private val HeaderLine    = raw"(?i)^(?:flowchart|graph)\s+(TD|TB|BT|LR|RL)\s*;?$$".r
  private val DirectionLine = raw"(?i)^direction\s+(TD|TB|BT|LR|RL)\s*;?$$".r
  private val SubgraphLine  = raw"(?i)^subgraph\s+([A-Za-z0-9_][A-Za-z0-9_-]*)(?:\s*\[(.*)\])?\s*;?$$".r
  private val StyleLine     = raw"(?i)^style\s+([A-Za-z0-9_][A-Za-z0-9_-]*)\s+(.+?)\s*;?$$".r
  private val ClassDefLine  = raw"(?i)^classDef\s+([^\s]+)\s+(.+?)\s*;?$$".r
  private val ClassLine     = raw"(?i)^class\s+([^\s]+)\s+(.+?)\s*;?$$".r
  private val LinkStyleLine = raw"(?i)^linkStyle\s+(default|\d+)\s+(.+?)\s*;?$$".r
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
    val stack            = mutable.Stack[String]()
    val nodeStyles       = mutable.Map[String, List[String]]()
    val classAssignments = mutable.Map[String, List[String]]()
    val classDefs        = mutable.LinkedHashMap[String, MermaidClassDef]()
    val linkStyles       = mutable.Map[Int, (List[String], Option[String])]()

    var direction: Option[String]              = None
    var defaultEdgeStyle: List[String]         = Nil
    var defaultEdgeInterpolate: Option[String] = None
    var sawHeader                              = false
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
          case _ if line.startsWith("%%") => ()
          case HeaderLine(dir) if !sawHeader =>
            direction = Some(dir.toUpperCase)
            sawHeader = true
          case HeaderLine(_) =>
            failure = Some(s"could not parse the Mermaid flowchart: duplicate header at line $lineNumber")
          case _ if !sawHeader =>
            failure = Some(s"could not parse the Mermaid flowchart: expected a flowchart header at line $lineNumber")
          case DirectionLine(_) => () // A subgraph-local layout hint does not change graph identity.
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
            if target.equalsIgnoreCase("default") then
              defaultEdgeStyle = styles
              defaultEdgeInterpolate = interpolate
            else linkStyles(target.toInt) = (styles, interpolate)
          case StyleLine(id, body) =>
            nodeStyles(id) = body.stripSuffix(";").split(",").iterator.map(_.trim).filter(_.nonEmpty).toList
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

        val vertexMap = vertices.iterator.map { case (id, vertex) =>
          id -> vertex.copy(
            styles = nodeStyles.getOrElse(id, vertex.styles),
            classes = (vertex.classes ++ classAssignments.getOrElse(id, Nil)).distinct
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
                classes = classAssignments.getOrElse(id, Nil)
              ),
            direction = direction,
            title = title,
            classDefs = classDefs.toMap,
            defaultEdgeStyle = defaultEdgeStyle,
            defaultEdgeInterpolate = defaultEdgeInterpolate
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
          .map(MermaidSourceScan.normalizeLabel)
          .filter(_.nonEmpty)
        (title, closing + 1)

  private def splitNames(value: String): List[String] =
    value.stripSuffix(";").split("[\\s,]+").iterator.map(_.trim).filter(_.nonEmpty).toList

  private def ignoredDirective(line: String): Boolean =
    val lower = line.toLowerCase
    lower.startsWith("click ") || lower.startsWith("accTitle:") || lower.startsWith("accDescr:")

  private def edgeType(arrow: String): String =
    if arrow.startsWith("<") then "double_arrow_point"
    else if !arrow.endsWith(">") then "arrow_open"
    else "arrow_point"

  private def edgeStroke(arrow: String): Option[String] = arrow match
    case "-.->" | "<-.->" | "-.-" => Some("dotted")
    case "==>" | "<==>" | "==="   => Some("thick")
    case _                        => None

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
