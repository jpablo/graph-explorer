package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.models.Attributable.idAttributeKey
import upickle.default.*

def quoteText(s: String): String = "\"" + s + "\""

def attribute(id:     String, value: String): String = s"$id=\"$value\""
def htmlAttribute(id: String, value: String): String = s"$id=<$value>"

extension (ast: DotAST)
  def edgeSeparator = if ast.tpe == "digraph" then "->" else "--"

  def render(keepInternal: Boolean): String =
    val body = ast.children
      .map(_.render(keepInternal, edgeSeparator))
      .filter(_.nonEmpty)
      .mkString("")
    val idStr = ast.id.map(s => quoteText(s) + " ").getOrElse(" ")
    s"${ast.tpe} $idStr{$body}"
  end render

extension (node: GraphElement)
  def render(keepInternal: Boolean, edgeSeparator: String): String =
    node match
      case Newline() => "\n"

      case Pad() => "  "

      case AttrStmt(target, attrList) =>
        val attrs = renderAttrList(attrList, keepInternal)
        if attrs.isEmpty then "" else s"$target $attrs"

      case EdgeStmt(edgeList, attrList) =>
        edgeList
          .map:
            case n: DotNodeId => quoteText(n.id)
            case s: Subgraph  => s.render(keepInternal, edgeSeparator)
          .mkString(s" $edgeSeparator") + renderAttrList(attrList, keepInternal)

      case StmtSep() => ""

      case NodeStmt(nodeId, attrList) =>
        quoteText(nodeId.id) + renderAttrList(attrList, keepInternal)

      case Comment() => ""

      case Subgraph(children, id) =>
        val idStr = id.getOrElse("")
        children.map(_.render(keepInternal, edgeSeparator)).mkString(s"subgraph $idStr {", "", "}")

private def renderAttrList(attrList: List[Attr], keepInternal: Boolean): String =
  attrList match
    case Nil => ""
    case attrs =>
      val attrsStrings =
        attrs
          .filter(a => keepInternal || a.id != idAttributeKey)
          .map:
            case Attr("label", raw: String) =>
              // Escape the label string
              val jsonEscaped = write(raw)
              attribute("label", jsonEscaped.slice(1, jsonEscaped.length - 1))

            case Attr(id, AttrEq(value, true))          => htmlAttribute(id, value)
            case Attr("style", "stroke-dasharray: 5,5") => attribute("style", "dashed")
            case a @ Attr(id, _)                        => attribute(id, a.value)

      if attrsStrings.isEmpty then ""
      else
        attrsStrings.mkString("[", ", ", "];")
