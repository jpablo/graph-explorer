package org.jpablo.graphexplorer.viewer.backends.mermaid

import scala.collection.mutable

/** TEST-ONLY approximation of the mermaid.js flowchart parser.
  *
  * Reads the Mermaid subset that `viewerGraphToMermaidText` emits and produces the
  * `MermaidGraph` the real (browser-only) parser would hand to `toViewerGraph`, so
  * text-level round trips can run on the JVM (see FeatureParitySpec). It is NOT the
  * product parse path: keep it aligned with the serializer's output, not with the
  * full mermaid.js grammar.
  */
object MermaidTestScanner:

  private val EdgeLine =
    raw"""^(\S+)\s+(<-->|<-\.->|<==>|-->|-\.->|==>|---|-\.-|===)(?:\|([^|]*)\|)?\s+(\S+)$$""".r
  private val SubgraphLine  = raw"""^subgraph\s+(\S+?)(?:\s+\[(.*)\])?$$""".r
  private val StyleLine     = raw"""^style\s+(\S+)\s+(.*)$$""".r
  private val ClassDefLine  = raw"""^classDef\s+(\S+)\s+(.*)$$""".r
  private val ClassLine     = raw"""^class\s+(\S+)\s+(.*)$$""".r
  private val LinkStyleLine = raw"""^linkStyle\s+(default|\d+)\s+(.*)$$""".r

  /** Bracket pairs in match order (most specific first). The shape names are the
    * mermaid.js vocabulary that `mermaidShapeToDot` accepts.
    */
  private val Brackets: List[(String, String, String)] = List(
    ("(((", ")))", "doublecircle"),
    ("((", "))", "circle"),
    ("([", "])", "stadium"),
    ("[(", ")]", "cylinder"),
    ("{{", "}}", "hexagon"),
    ("[/", "/]", "parallelogram"),
    ("[/", "\\]", "trapezoid"),
    ("[\\", "/]", "trapezoid-alt"),
    ("{", "}", "diamond"),
    ("[", "]", "square"),
    ("(", ")", "round")
  )

  def scan(text: String): MermaidGraph =
    var direction: Option[String] = None
    var title:     Option[String] = None

    val vertices         = mutable.LinkedHashMap[String, MermaidVertex]()
    val edges            = mutable.ListBuffer[MermaidEdge]()
    val subgraphsInOrder = mutable.ListBuffer[String]()
    val subgraphTitles   = mutable.Map[String, Option[String]]()
    val subgraphMembers  = mutable.Map[String, mutable.ListBuffer[String]]()
    val stack            = mutable.Stack[String]()
    val nodeStyles       = mutable.Map[String, List[String]]()
    val classAssignments = mutable.Map[String, List[String]]()
    val classDefs        = mutable.LinkedHashMap[String, MermaidClassDef]()
    val linkStyles       = mutable.Map[Int, (List[String], Option[String])]()

    var defaultEdgeStyle:       List[String]   = Nil
    var defaultEdgeInterpolate: Option[String] = None

    def registerVertex(id: String, v: => MermaidVertex): Unit =
      if !vertices.contains(id) then vertices(id) = v

    def addMember(id: String): Unit =
      stack.headOption.foreach(parent => subgraphMembers(parent) += id)

    /** Split a css body into (declarations, interpolate). */
    def splitCss(body: String): (List[String], Option[String]) =
      val decls           = body.split(",").iterator.map(_.trim).filter(_.nonEmpty).toList
      val (interp, rest)  = decls.partition(_.toLowerCase.startsWith("interpolate:"))
      (rest, interp.headOption.map(_.split(":", 2)(1).trim))

    val rawLines = text.linesIterator.toVector
    var start    = 0
    // Front matter: --- / title: x / ---
    if rawLines.headOption.exists(_.trim == "---") then
      start = 1
      while start < rawLines.length && rawLines(start).trim != "---" do
        val l = rawLines(start).trim
        if l.startsWith("title:") then title = Some(l.stripPrefix("title:").trim)
        start += 1
      start += 1

    rawLines.drop(start).map(_.trim).filter(_.nonEmpty).foreach {
      case l if l.startsWith("%%") => ()
      case l if l.startsWith("flowchart ") || l.startsWith("graph ") =>
        direction = Some(l.split("\\s+")(1))
      case "end" =>
        if stack.nonEmpty then stack.pop()
      case SubgraphLine(id, titleOrNull) =>
        addMember(id) // a subgraph opened inside another is the parent's member
        stack.push(id)
        subgraphsInOrder += id
        subgraphTitles(id) = Option(titleOrNull).map(MermaidSourceScan.normalizeLabel)
        subgraphMembers.getOrElseUpdate(id, mutable.ListBuffer())
      case ClassDefLine(name, body) =>
        classDefs(name) = MermaidClassDef(styles = body.split(",").map(_.trim).filter(_.nonEmpty).toList)
      case ClassLine(id, classes) =>
        classAssignments(id) =
          classAssignments.getOrElse(id, Nil) ++ classes.split("[ ,]+").filter(_.nonEmpty).toList
      case LinkStyleLine(target, body) =>
        val (styles, interpolate) = splitCss(body)
        if target == "default" then
          defaultEdgeStyle = styles
          defaultEdgeInterpolate = interpolate
        else linkStyles(target.toInt) = (styles, interpolate)
      case StyleLine(id, body) =>
        nodeStyles(id) = body.split(",").map(_.trim).filter(_.nonEmpty).toList
      case EdgeLine(src, arrow, labelOrNull, dst) =>
        val stroke = arrow match
          case "-.->" | "<-.->" | "-.-" => Some("dotted")
          case "==>" | "<==>" | "==="   => Some("thick")
          case _                        => None
        // mirror mermaid.js's destructLink typing: leading `<` = arrows at both ends,
        // no trailing `>` = open link (no arrows)
        val edgeType =
          if arrow.startsWith("<") then Some("double_arrow_point")
          else if !arrow.endsWith(">") then Some("arrow_open")
          else Some("arrow_point")
        edges += MermaidEdge(
          start = src,
          end = dst,
          text = Option(labelOrNull).map(MermaidSourceScan.normalizeLabel).filter(_.nonEmpty),
          stroke = stroke,
          edgeType = edgeType
        )
        registerVertex(src, MermaidVertex(id = src, text = src))
        registerVertex(dst, MermaidVertex(id = dst, text = dst))
      case l =>
        parseNodeLine(l).foreach { v =>
          registerVertex(v.id, v)
          addMember(v.id)
        }
    }

    val edgeList = edges.toList.zipWithIndex.map { case (e, idx) =>
      linkStyles.get(idx) match
        case Some((styles, interpolate)) => e.copy(styles = styles, interpolate = interpolate)
        case None                        => e
    }

    // mermaid.js gives ANY styled id a vertices-dictionary entry — including subgraph
    // ids, which is where toViewerGraph harvests group styles from. Mirror that.
    nodeStyles.keys.foreach { id =>
      if !vertices.contains(id) then vertices(id) = MermaidVertex(id = id, text = id)
    }

    val vertexMap = vertices.map { case (id, v) =>
      id -> v.copy(
        styles = nodeStyles.getOrElse(id, Nil),
        classes = (v.classes ++ classAssignments.getOrElse(id, Nil)).distinct
      )
    }.toMap

    MermaidGraph(
      vertices = vertexMap,
      edges = edgeList,
      subgraphs = subgraphsInOrder.toList.map { id =>
        MermaidSubgraph(
          id = id,
          title = subgraphTitles(id),
          nodes = subgraphMembers(id).toList,
          classes = classAssignments.getOrElse(id, Nil)
        )
      },
      direction = direction,
      title = title,
      classDefs = classDefs.toMap,
      defaultEdgeStyle = defaultEdgeStyle,
      defaultEdgeInterpolate = defaultEdgeInterpolate
    )

  /** Parse a standalone node statement: `id`, `id[Label]`, `id{{Label}}:::class`, ... */
  private def parseNodeLine(line: String): Option[MermaidVertex] =
    val (base, classes) = line.indexOf(":::") match
      case -1  => (line, Nil)
      case idx => (line.substring(0, idx), line.substring(idx + 3).split("[ ,]+").filter(_.nonEmpty).toList)
    val idEnd      = base.indexWhere(c => !MermaidSourceScan.isIdentifierChar(c))
    val (id, rest) = if idEnd == -1 then (base, "") else (base.substring(0, idEnd), base.substring(idEnd).trim)
    if id.isEmpty then None
    else if rest.isEmpty then Some(MermaidVertex(id = id, text = id, classes = classes))
    else
      Brackets.collectFirst {
        case (open, close, shape)
            if rest.startsWith(open) && rest.endsWith(close) && rest.length >= open.length + close.length =>
          val inner = rest.substring(open.length, rest.length - close.length)
          MermaidVertex(
            id = id,
            text = MermaidSourceScan.normalizeLabel(inner),
            shape = Some(shape),
            classes = classes
          )
      }
