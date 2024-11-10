package org.jpablo.graphexplorer.viewer.formats.dot

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.formats.dot.ast.DotAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.ViewerNode
import org.scalajs.dom
import org.scalajs.dom.SVGSVGElement

import scala.util.{Failure, Success}

case class Dot(value: String):
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

object Dot:
  private val gvInstance = new Graphviz

  lazy val empty = Dot("digraph G { } ")

  extension (dotAST: DotAST)
    def toDot: Dot =
      Dot(dotAST.render(true))

    def toViewerGraph: ViewerGraph =
      ViewerGraph(
        arrows = dotAST.allArrows,
        nodes  = dotAST.allNodesIds.map(ViewerNode.node)
      )

  extension (dot: Dot)
    def toSvg: Signal[SVGSVGElement] =
      gvInstance.renderToSvg(dot)

end Dot

