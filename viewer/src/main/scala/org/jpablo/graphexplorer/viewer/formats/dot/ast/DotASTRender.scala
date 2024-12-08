package org.jpablo.graphexplorer.viewer.formats.dot.ast

import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.formats.dot.ast.renderFormat.DotFormatter

def attribute(id:     String, value: String): String = s"$id=\"$value\""
def htmlAttribute(id: String, value: String): String = s"$id=<$value>"

extension (ast: DotAST)
  def render(keepInternal: Boolean = false): String =
    DotFormatter.renderFormat(ast, keepInternal)

  def renderToDot: DotText =
    DotText(ast.render(keepInternal = true), ast.version)

