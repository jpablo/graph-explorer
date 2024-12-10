package org.jpablo.graphexplorer.viewer.formats.dot

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DotAST
import org.scalajs.dom.SVGSVGElement

import scala.util.{Failure, Success}

case class DotText(value: String):
//  org.scalajs.dom.console.log(value)

  override def toString: String =
    value

  // TODO: handle errors
  def parseAST: List[DotAST] =
    if value.isEmpty then List.empty
    else
      DotParserT.parse(value) match
        case Failure(exception) =>
          dom.console.error(s"<== after DotParserT.parse")
          pprint.log(exception)
          dom.console.error(value)
          List.empty
        case Success(asts) =>
//          dom.console.debug(s"<== after DotParserT.parse: $asts")
          asts //.map(_.copy(version = version))

  def toSvg: Signal[SVGSVGElement] =
    DotText.gvInstance.renderToSvg(this)

object DotText:
  private val gvInstance = new Graphviz

  lazy val empty = DotText("digraph G { } ")


end DotText
