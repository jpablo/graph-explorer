package org.jpablo.graphexplorer.viewer.formats.dot

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DotAST
import org.scalajs.dom
import org.scalajs.dom.SVGSVGElement

import scala.util.{Failure, Success}

case class DotText(value: String):
//  org.scalajs.dom.console.log(value)

  override def toString: String =
    value

  // TODO: handle errors
  val buildAST: List[DotAST] =
    if value.isEmpty then List.empty
    else
      DotParserT.parse(value) match
        case Failure(exception) =>
          dom.console.error(exception.toString)
          List.empty
        case Success(asts) => asts

object DotText:
  private val gvInstance = new Graphviz

  lazy val empty = DotText("digraph G { } ")

  extension (dot: DotText)
    def toSvg: Signal[SVGSVGElement] =
      gvInstance.renderToSvg(dot)

end DotText

