package org.jpablo.graphexplorer.viewer.formats.dot

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DotAST

import scala.util.{Success, Try}

case class DotText(value: String):

  override def toString: String =
    value

  def parseAST: Try[List[DotAST]] =
    if value.isEmpty then
      Success(Nil)
    else
      DotParserT.parse(value)

  def toSvg: Signal[Option[ReactiveSvgElement[dom.svg.SVG]]] =
    DotText.gvInstance.renderToSvg(this)

object DotText:
  private val gvInstance = new Graphviz

  lazy val empty = DotText("digraph G { }")

end DotText
