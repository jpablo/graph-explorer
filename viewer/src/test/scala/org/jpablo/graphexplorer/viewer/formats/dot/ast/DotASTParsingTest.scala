package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
//import upickle.default.*

import scala.scalajs.js.Dynamic.global as g

class DotASTParsingTest extends ScalaCheckSuite:
  val fs = g.require("fs")

  test("parse subgroup"):
    val sample: String = fs.readFileSync("viewer/src/test/resources/subgroup.dot", "utf8").asInstanceOf[String]
    val ast = DotText(sample, 0).parseAST
    pprint.pprintln(ast, showFieldNames = false)
