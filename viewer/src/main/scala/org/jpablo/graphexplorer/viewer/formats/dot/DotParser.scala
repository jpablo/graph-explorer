package org.jpablo.graphexplorer.viewer.formats.dot

import org.jpablo.graphexplorer.viewer.formats.dot.ast.DotAST
import upickle.default.*

import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.scalajs.js.annotation.*
import scala.util.Try

// https://github.com/magjac/graphviz-visual-editor

@js.native
@JSImport("./node_modules/dot-parser", JSImport.Namespace)
object DotParser extends js.Object:
  def parse(dotString: String): js.Object = js.native

object DotParserT:
  def parse(dotString: String): Try[List[DotAST]] =
    for
      j <- Try(DotParser.parse(dotString))
      // _ = dom.console.log("DotParser.parse", j) // double slash (\\n)
      // Stringify will escape double quotes and slashes
      jsonStr = JSON.stringify(j)
      // _ = dom.console.log("JSON.stringify", jsonStr) // double slash (\\n)
      // _ = pprint.log(JSON.stringify(j)) // four slashes (\\\\n)
      ast <- Try(read[List[DotAST]](jsonStr))
      // _ = pprint.log(ast) // double slash (\\n)
    yield ast
