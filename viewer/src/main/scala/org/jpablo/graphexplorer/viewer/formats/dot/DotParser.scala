package org.jpablo.graphexplorer.viewer.formats.dot

import org.jpablo.graphexplorer.viewer.formats.dot.ast.DotAST
import upickle.default.*

import scala.scalajs.js
import scala.scalajs.js.JSON
import scala.scalajs.js.annotation.*
import scala.util.Try

// https://github.com/magjac/graphviz-visual-editor

@js.native
@JSImport("dot-parser", JSImport.Namespace)
object DotParser extends js.Object:
  def parse(dotString: String): js.Object = js.native

object DotParserT:
  def parse(dotString: String): Try[List[DotAST]] =
    for
      j <- Try(DotParser.parse(dotString))
      // parsing keeps \" but escapes \\ (i.e. duplicate the backslashes)
      // "a \"title\\" becomes "a \"title\\\\"
      // _ = dom.console.log(dotString)
      str = JSON.stringify(j)
      // read will unescape labels
      // i.e. replace \" with " and \\ with \ in strings
      // but will keep \\ in the string
      // at this point the label is """a "title\\\\"""
      normalizedStr = str.replaceAll("""\\\\""", """\\""")
      // Is this carpet replacement Ok? or do we need to focus only on labels?
      ast <- Try(read[List[DotAST]](normalizedStr)) // ❌
    yield ast
