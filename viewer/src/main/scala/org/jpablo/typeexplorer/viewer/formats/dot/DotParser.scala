package org.jpablo.typeexplorer.viewer.formats.dot

import org.jpablo.typeexplorer.viewer.formats.dot.ast.DiGraph
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
  def parse(dotString: String): Try[List[DiGraph]] =
    Try:
      val j = DotParser.parse(dotString)
      val str = JSON.stringify(j)
      read[List[DiGraph]](str)
