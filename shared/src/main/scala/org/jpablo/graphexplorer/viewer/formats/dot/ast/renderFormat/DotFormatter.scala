package org.jpablo.graphexplorer.viewer.formats.dot.ast.renderFormat

import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType.digraph
import org.jpablo.graphexplorer.viewer.models.ViewerElement.idAttributeKey

class DotFormatter(ast: DotAST, keepInternal: Boolean = false, paddingSize: Int = 4):
  /** Renders a DotAST to a string in DOT format.
    *
    * @param ast
    *   The DotAST to render.
    * @param keepInternal
    *   If true, internal attributes (like id) will be included in the output.
    * @param paddingSize
    *   The number of spaces to use for indentation.
    * @return
    *   A string representation of the DotAST in DOT format.
    */
  def render(): String =
    val graphId = ast.id.map(id => s" \"$id\"").getOrElse(" G")
    val body = ast.children
      .map(elem => renderGraphElement(elem, 1))
      .filter(_.nonEmpty)
      .mkString("\n")

    cleanOutput(
      s"""${ast.tpe}$graphId {
         |$body
         |}""".stripMargin
    )

  private def padding(level: Int): String = " " * (level * paddingSize)

  private def formatValue(value: AttrValue): String =
    value.value match
      case AttrEq(value, true)  => s"""<$value>"""
      case AttrEq(value, false) => s""""$value""""
      case value                => s""""$value""""

  private def formatNodeId(id: DotNodeId): String =
    val port = id.port.map(p => s":\"${p.id}\"").getOrElse("")
    s"\"${id.id}\"$port"

  private def formatKeyValue(key: String, value: AttrValue): String =
    s"$key=${formatValue(value)}"

  private def renderAttributes(attributes: List[Attr], level: Int): String =
    val filteredAttrs =
      attributes.filterNot(attr =>
        !keepInternal && attr.id == idAttributeKey.value
      ) // Skip rendering of id attributes
    if filteredAttrs.isEmpty then
      ""
    else if filteredAttrs.length <= 1 then
      val attrString = filteredAttrs.map(attr => formatKeyValue(attr.id, attr.attrEq)).mkString(", ")
      s" [$attrString]"
    else
      val pad         = padding(level + 1)
      val attrStrings = filteredAttrs.map(attr => formatKeyValue(s"$pad${attr.id}", attr.attrEq)).mkString(",\n")
      s" [\n$attrStrings\n${padding(level)}]"

  private def renderGraphElement(element: GraphElement, level: Int): String =
    val pad = padding(level)
    element match
      case Newline()        => ""
      case Pad()            => ""
      case Comment()        => ""
      case AttrStmt(_, Nil) => ""
      case AttrStmt(target, attrs) =>
        s"$pad$target${renderAttributes(attrs, level)};"

      case NodeStmt(nodeId, attrs) =>
        if attrs.isEmpty then
          s"$pad${formatNodeId(nodeId)}"
        else
          s"$pad${formatNodeId(nodeId)}${renderAttributes(attrs, level)};"

      case EdgeStmt(edgeList, attrs) =>
        val edgeOp = if ast.tpe == digraph.toString then "->" else "--"
        val edges = edgeList
          .map:
            case n: DotNodeId => formatNodeId(n)
            case s: SubGraph  => renderSubgraph(s, level)
          .mkString(s" $edgeOp ")
        s"$pad$edges${renderAttributes(attrs, level)};"

      case StmtSep() => ""

      case s: SubGraph => renderSubgraph(s, level)

  private def renderSubgraph(subgraph: SubGraph, level: Int): String =
    val pad        = padding(level)
    val subgraphId = subgraph.id.map(id => s" $id").getOrElse("")
    val body = subgraph.children.map(elem => renderGraphElement(elem, level + 1))
      .filter(_.nonEmpty)
      .mkString("\n")
    s"""${pad}subgraph$subgraphId {
       |$body
       |$pad}""".stripMargin

  private def cleanOutput(s: String): String =
    s.split('\n')
      .map(_.replaceAll("\\s+$", ""))
      .filter(_.nonEmpty)
      .mkString("\n")

end DotFormatter
