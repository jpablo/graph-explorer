package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.{OneTimeOwner, Owner}
import com.raquo.airstream.state.Var
import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.models.NodeId
import upickle.default.*

import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as g

class SourceFlowSpec extends ScalaCheckSuite:
  val fs = g.require("fs")
  val styles: String = fs.readFileSync("viewer/src/test/resources/styles.dot", "utf8").asInstanceOf[String]
  given owner: Owner = OneTimeOwner(() => ())

  val hiddenNodesV: Var[Set[NodeId]] = Var(Set.empty)
  val viewerState = SourceFlow(styles, hiddenNodesV.signal)
  val fullAST = viewerState.fullAST.observe().now()

  test("fullAST sanity check"):
    assert(fullAST == referenceAST)

  test("fullAST should attach a consecutive id to each edge"):
    val edgeIds = fullAST.children.collect { case e: EdgeStmt => e.idAttr }
    assert(edgeIds == List("1", "2", "3"))

  lazy val referenceAST =
    DiGraphAST(
      List(
        Newline(),
        Pad(),
        NodeStmt(DotNodeId("A", None), List(Attr("shape", "diamond"))),
        Newline(),
        Pad(),
        NodeStmt(DotNodeId("B", None), List(Attr("shape", "box"))),
        Newline(),
        Pad(),
        NodeStmt(DotNodeId("C", None), List(Attr("shape", "circle"))),
        Newline(),
        Pad(),
        EdgeStmt(
          List(DotNodeId("A", None), DotNodeId("B", None)),
          List(Attr("id", "1"), Attr("style", "dashed"), Attr("color", "grey"))
        ),
        Newline(),
        Pad(),
        EdgeStmt(
          List(DotNodeId("A", None), DotNodeId("C", None)),
          List(Attr("id", "2"), Attr("color", "black:invis:black"))
        ),
        Newline(),
        Pad(),
        EdgeStmt(
          List(DotNodeId("A", None), DotNodeId("D", None)),
          List(Attr("id", "3"), Attr("penwidth", "5"), Attr("arrowhead", "none"))
        ),
        Newline()
      ),
      Some("D")
    )

end SourceFlowSpec
