package org.jpablo.graphexplorer.viewer.formats.dot

import org.jpablo.graphexplorer.viewer.formats.dot.ast.DiGraphAST
import org.scalajs.dom
import upickle.default.*

import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.scalajs.js.annotation.*
import scala.util.Try

// https://github.com/magjac/graphviz-visual-editor

@js.native
@JSImport("/dotParser.js", JSImport.Namespace)
object DotParser extends js.Object:
  def parse(dotString: String): js.Object = js.native

object DotParserT:
  def parse(dotString: String): Try[List[DiGraphAST]] =
    for
      j <- Try(DotParser.parse(dotString))
//      _ = dom.console.log(j)
      str = JSON.stringify(j)
      ast <-
        Try(read[List[DiGraphAST]](str)) match
          case f @ scala.util.Failure(exception) =>
            dom.console.error("==> Error in DotParserT.parse !")
            dom.console.error(exception.toString)
            dom.console.error(str)
            dom.console.error(dotString)
            dom.console.error("<== Error in DotParserT.parse !")
            f
          case s @ scala.util.Success(_) =>
            s
    yield ast
