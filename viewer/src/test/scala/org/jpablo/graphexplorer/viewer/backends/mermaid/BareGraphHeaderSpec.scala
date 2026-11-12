package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.backends.mermaid.RealMermaid.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** A Mermaid flowchart can start with `graph` and no direction.
  *
  * The Mermaid lexer gives `graph` the directions LR, RL, TB, BT, TD, BR, >, <, ^ and v, and a `graph` line with no
  * direction is a flowchart from top to bottom. The app knows `graph` only with LR, RL, TB, BT or TD and a space. Thus
  * it gives DOT for the other flowcharts, because the DOT scan accepts each line that starts with the word `graph`.
  *
  * This spec reads each text with real Mermaid, as the canvas does, and then asks the app for the language of the text.
  * PastedDiagramSpec (shared) has the same texts, and it checks them on the JVM with no real Mermaid.
  */
class BareGraphHeaderSpec extends FunSuite with LoggedChecks:

  def checkPrefix = "BareGraphHeaderSpec"

  private val Flowcharts = Vector(
    "graph\n  A --> B",
    "graph\nA --> B",
    "graph \n  A --> B",
    "graph\r\n  A --> B",
    "graph\n  A{Decision} --> B",
    "---\ntitle: Plan\n---\ngraph\n  A --> B",
    "graph >\n  A --> B",
    "graph <\n  A --> B",
    "graph ^\n  A --> B",
    "graph v\n  A --> B",
    "graph>\n  A --> B",
    "graph BR\n  A --> B",
    "graph\tTD\n  A --> B"
  )

  private val DotGraphs = Vector(
    "graph {\n  a -- b\n}",
    "graph{ a -- b }",
    "graph G {\n  a -- b\n}",
    "graph\n{\n  a -- b\n}",
    "graph\nG {\n  a -- b\n}",
    "graph\n\"my graph\" {\n  a -- b\n}",
    "strict graph {\n  a -- b\n}",
    "strict graph\n{\n  a -- b\n}",
    "graph v { a -- b }",
    "graph BR { a -- b }"
  )

  test("the app detects Mermaid for a `graph` flowchart with no direction, or with a direction other than LR RL TB BT TD"):
    oneByOne(Flowcharts)(text => readWithMermaid(text).map(text -> _)).map { results =>
      // Setup: real Mermaid reads each text as a flowchart with the nodes A and B.
      val notRead = results.collect {
        case (text, Failure(e))                                                => s"${literal(text)}: ${firstLine(e)}"
        case (text, Success(g)) if g.nodes.keySet.map(_.value) != Set("A", "B") => s"${literal(text)}: the nodes ${g.nodes.keySet}"
      }
      check(notRead.isEmpty, s"setup: real Mermaid does not read ${notRead.size} of ${Flowcharts.size} texts:\n${notRead.mkString("\n")}")
      val wrong = Flowcharts.map(text => text -> DiagramFormat.detect(text)).filter(_._2 != DiagramFormat.Mermaid)
      check(
        wrong.isEmpty,
        s"DiagramFormat.detect does not give Mermaid for ${wrong.size} of ${Flowcharts.size} flowcharts that real Mermaid reads:\n" +
          wrong.map((text, format) => s"${literal(text)} -> $format").mkString("\n")
      )
    }

  test("the app detects DOT for a DOT graph that starts with `graph`"):
    // A guard for the fix of the test above. It passes before the fix.
    oneByOne(DotGraphs)(text => readWithMermaid(text).map(text -> _)).map { results =>
      val read = results.collect { case (text, Success(_)) => literal(text) }
      check(read.isEmpty, s"setup: real Mermaid reads ${read.size} of ${DotGraphs.size} DOT graphs:\n${read.mkString("\n")}")
      val wrong = DotGraphs.map(text => text -> DiagramFormat.detect(text)).filter(_._2 != DiagramFormat.DOT)
      check(
        wrong.isEmpty,
        s"DiagramFormat.detect does not give DOT for ${wrong.size} of ${DotGraphs.size} DOT graphs:\n" +
          wrong.map((text, format) => s"${literal(text)} -> $format").mkString("\n")
      )
    }
