package org.jpablo.typeexplorer.viewer.backends.graphviz

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@js.native
@JSGlobal("DotParser")
object DotParser extends js.Object {
  def parse(dot: String): js.Object = js.native
  def SyntaxError: js.Dynamic = js.native
  def StartRules: js.Array[String] = js.native
}

// Usage
val result = DotParser.parse("your DOT string here")


