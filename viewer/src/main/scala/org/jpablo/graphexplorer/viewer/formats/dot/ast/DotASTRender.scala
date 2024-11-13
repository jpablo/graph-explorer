package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.models.Attributable.idAttributeKey

extension (ast: DotAST)
  def edgeSeparator = if ast.tpe == "digraph" then "->" else "--"

  def render(keepInternal: Boolean): String =
    val body = ast.children
      .map(_.render(keepInternal, edgeSeparator))
      .filter(_.nonEmpty)
      .mkString("")
    val idStr = ast.id.map(id => s"\"$id\" ").getOrElse(" ")
    s"${ast.tpe} $idStr{$body}"
  end render

extension (node: GraphElement)
  def render(keepInternal: Boolean, edgeSeparator: String): String =
    node match
      case Newline() => "\n"

      case Pad() => "  "

      case AttrStmt(target, attrList) =>
        val attrs = renderAttrList(keepInternal, attrList)
        if attrs.isEmpty then "" else s"$target $attrs"

      case EdgeStmt(edgeList, attrList) =>
        edgeList
          .map:
            case n: DotNodeId => s"\"${n.id}\""
            case s: Subgraph  => s.render(keepInternal, edgeSeparator)
          .mkString(s" $edgeSeparator") + renderAttrList(keepInternal, attrList)

      case StmtSep() => ""

      case NodeStmt(nodeId, attrList) =>
        "\"" + nodeId.id + "\"" + renderAttrList(keepInternal, attrList)

      case Comment() => ""

      case Subgraph(children, id) =>
        val idStr = id.getOrElse("")
        children.map(_.render(keepInternal, edgeSeparator)).mkString(s"subgraph $idStr {", "", "}")

private def renderAttrList(keepInternal: Boolean, attrList: List[Attr]): String =
  attrList match
    case Nil => ""
    case attrs =>
      val attrsStrings =
        attrs
          .filter(a => keepInternal || a.id != idAttributeKey)
          .map:
            case Attr(id, AttrEq(value, html)) =>
              if html then s"$id=<$value>"
              else s"$id=\"$value\""
            case Attr("style", "stroke-dasharray: 5,5") => s"style=\"dashed\""
            case Attr(id, s: String)                    => s"$id=\"$s\""
      if attrsStrings.isEmpty then ""
      else
        attrsStrings.mkString(" [", ", ", "];")
