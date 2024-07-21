package org.jpablo.typeexplorer.viewer.backends.graphviz

import scala.scalajs.js
import org.scalajs.dom
import scala.scalajs.js.annotation.*

@js.native
@JSGlobal("DotParser") // JavaScriptException: ReferenceError: DotParser is not defined
object DotParser extends js.Object {
  dom.console.log("[DotParser] init")
  def parse(dot: String): js.Object = js.native
  def SyntaxError: js.Dynamic = js.native
  def StartRules: js.Array[String] = js.native
}
