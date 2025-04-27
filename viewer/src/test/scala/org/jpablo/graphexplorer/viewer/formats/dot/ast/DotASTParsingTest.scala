package org.jpablo.graphexplorer.viewer.formats.dot.ast

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.GraphType.digraph
import scala.util.Success
import scala.scalajs.js.Dynamic.global as g

class DotASTParsingTest extends ScalaCheckSuite:
  val fs = g.require("fs")

  test("parse subgroup"):
    val sample: String = fs.readFileSync("viewer/src/test/resources/styles.dot", "utf8").asInstanceOf[String]
    val ast = DotText(sample).parseAST
    assertEquals(ast, Success(expected))


  def expected =
    List(
      DotAST(
        digraph.toString,
        List(
          Newline(),
          Pad(),
          AttrStmt("graph", List(Attr("label", AttrValue("Title: \"quoted text\"")))),
          Newline(),
          Pad(),
          NodeStmt(DotNodeId("A", None), List(Attr("shape", AttrValue("diamond")))),
          Newline(),
          Pad(),
          NodeStmt(DotNodeId("B", None), List(Attr("shape", AttrValue("box")))),
          Newline(),
          Pad(),
          NodeStmt(DotNodeId("C", None), List(Attr("shape", AttrValue("circle")))),
          Newline(),
          Pad(),
          EdgeStmt(
            List(DotNodeId("A", None), DotNodeId("B", None)),
            List(Attr("style", AttrValue("dashed")), Attr("color", AttrValue("grey")))
          ),
          Newline(),
          Pad(),
          EdgeStmt(
            List(DotNodeId("A", None), DotNodeId("C", None)),
            List(Attr("color", AttrValue("black:invis:black")))
          ),
          Newline(),
          Pad(),
          EdgeStmt(
            List(DotNodeId("A", None), DotNodeId("D", None)),
            List(Attr("penwidth", AttrValue("5")), Attr("arrowhead", AttrValue("none")))
          ),
          Newline()
        ),
        Some("D")
      )
    )
