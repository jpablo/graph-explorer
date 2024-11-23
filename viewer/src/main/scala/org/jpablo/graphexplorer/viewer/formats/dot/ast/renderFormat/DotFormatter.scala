package org.jpablo.graphexplorer.viewer.formats.dot.ast.renderFormat

import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.models.Attributable.idAttributeKey

object DotFormatter:
  def renderFormat(ast: DotAST, keepInternal: Boolean = false, paddingSize: Int = 4): String =
    def padding(level: Int): String = " " * (level * paddingSize)

    def formatValue(value: String): String = s""""$value""""

    def formatNodeId(id: String): String = s""""$id""""
    
    def renderAttributes(attributes: List[Attr], level: Int): String =
      val filteredAttrs = attributes
        .filterNot(attr => !keepInternal && attr.id == idAttributeKey) // Skip rendering of id attributes
      if filteredAttrs.isEmpty then
        ""
      else if filteredAttrs.length <= 2 then
        val attrString = filteredAttrs
          .map(attr => s"${attr.id} = ${formatValue(attr.value)}")
          .mkString(", ")
        s" [$attrString]"
      else
        val pad = padding(level + 1)
        val attrStrings = filteredAttrs
          .map(attr => s"$pad${attr.id} = ${formatValue(attr.value)}")
          .mkString(",\n")
        s" [\n$attrStrings\n${padding(level)}]"

    def renderGraphElement(element: GraphElement, level: Int): String =
      val pad = padding(level)
      element match
        case Newline()             => ""
        case Pad()                 => ""
        case Comment()             => ""
        case AttrStmt(_, Nil) => ""
        case AttrStmt(target, attrs) =>
          s"$pad$target${renderAttributes(attrs, level)};"

        case NodeStmt(nodeId, attrs) =>
          if attrs.isEmpty then
            s"$pad${formatNodeId(nodeId.id)}"
          else
            s"$pad${formatNodeId(nodeId.id)}${renderAttributes(attrs, level)};"

        case EdgeStmt(edgeList, attrs) =>
          val edgeOp = if ast.tpe == "digraph" then "->" else "--"
          val edges = edgeList.map {
            case n: DotNodeId => formatNodeId(n.id)
            case s: Subgraph  => renderSubgraph(s, level)
          }.mkString(s" $edgeOp ")
          s"$pad$edges${renderAttributes(attrs, level)};"

        case StmtSep() => ""

        case s: Subgraph => renderSubgraph(s, level)

    def renderSubgraph(subgraph: Subgraph, level: Int): String =
      val pad = padding(level)
      val subgraphId = subgraph.id.map(id => s" $id").getOrElse("")
      val body = subgraph.children.map(elem => renderGraphElement(elem, level + 1))
        .filter(_.nonEmpty)
        .mkString("\n")
      s"""${pad}subgraph$subgraphId {
         |$body
         |$pad}""".stripMargin

    def cleanOutput(s: String): String =
      s.split('\n')
        .map(_.replaceAll("\\s+$", ""))
        .filter(_.nonEmpty)
        .mkString("\n")

    val graphId = ast.id.map(id => s" $id").getOrElse("")
    val body = ast.children
      .map(elem => renderGraphElement(elem, 1))
      .filter(_.nonEmpty)
      .mkString("\n")

    cleanOutput(
      s"""${ast.tpe}$graphId {
         |$body
         |}""".stripMargin
    )

end DotFormatter
