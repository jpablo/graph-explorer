package org.jpablo.typeexplorer.viewer.formats.dot

import org.jpablo.typeexplorer.viewer.formats.dot.ast.DiGraphAST
import org.scalajs.dom
import upickle.default.*

import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.scalajs.js.annotation.*
import scala.util.Try

@js.native
@JSImport("/dotParser.js", JSImport.Namespace)
object DotParser extends js.Object:
  def parse(dotString: String): js.Object = js.native

object DotParserT:
  def parse(dotString: String): Try[List[DiGraphAST]] =
    for
      j <- Try(DotParser.parse(dotString))
      str = JSON.stringify(j)
      ast <-
        Try(read[List[DiGraphAST]](str)) match
          case f @ scala.util.Failure(exception) =>
            dom.console.log("==> Error in DotParserT.parse !")
            dom.console.log(exception.toString)
            dom.console.log(str)
            dom.console.log(dotString)
            dom.console.log("<== Error in DotParserT.parse !")
            f
          case s @ scala.util.Success(value) =>
            dom.console.log("<== Success in DotParserT.parse !")
            s
    yield ast
