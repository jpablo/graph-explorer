package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

/** A node in a Mermaid group does not cover the title of the group.
  *
  * Mermaid wraps a long group title to two or more lines, but its layout keeps
  * the space of one line only. Thus the first nodes of the group covered the
  * second line of the title. These tests draw with the real Mermaid backend in
  * the browser, as the canvas does, and measure the drawn boxes.
  */
class ClusterTitleOverlapSpec extends FunSuite with TestHelpers:

  /** In the Playwright runner, a munit failure arrives with an empty message.
    * The console reaches the log, and an `AssertionError` keeps its message.
    */
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then
      println(s"[ClusterTitleOverlapSpec] $clue")
      throw new AssertionError(clue)

  private def intersect(a: dom.DOMRect, b: dom.DOMRect): Boolean =
    a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top

  /** For each group: its id, the height of its title, and the nodes that cover the title. */
  private def overlaps(svg: dom.svg.SVG): Seq[(String, Double, Seq[String])] =
    val clusters = svg.querySelectorAll("g.cluster")
    val nodes    = svg.querySelectorAll("g.node")
    (0 until clusters.length).flatMap: i =>
      val cluster = clusters(i)
      Option(cluster.querySelector(".cluster-label")).map: label =>
        val box = label.getBoundingClientRect()
        val covering =
          (0 until nodes.length)
            .map(nodes(_))
            .filter(n => intersect(n.getBoundingClientRect(), box))
            .map(_.id)
        (cluster.id, box.height, covering)

  private def drawAndCheck(name: String, text: String) =
    RealMermaid.drawWithMermaid(text).map:
      case Failure(e) => check(false, s"$name: Mermaid failed: ${RealMermaid.firstLine(e)}")
      case Success(svg) =>
        // Measure on the page, as the canvas shows the drawing.
        dom.document.body.appendChild(svg)
        try
          val found = overlaps(svg)
          check(found.nonEmpty, s"$name: no group title in the drawing")
          found.foreach: (id, height, covering) =>
            check(covering.isEmpty, s"$name: group $id (title height $height px) is covered by ${covering.mkString(", ")}")
        finally svg.remove()

  test("a group title of two lines is not covered by the nodes of the group"):
    drawAndCheck(
      "two lines",
      """flowchart TD
        |  subgraph FIXED["Fixed: the variant and the model"]
        |    MFS["mem-fraction-static = 0.55"]
        |    TOT["device total = 95.6 GiB"]
        |  end
        |  MFS --> X
        |  TOT --> X""".stripMargin
    )

  test("a group title of three lines is not covered by the nodes of the group"):
    drawAndCheck(
      "three lines",
      """flowchart TD
        |  subgraph START["Decided once at the engine start, from the free memory of the device at that time"]
        |    FREE["free memory at start: 60 … 94.25 GiB"]
        |    STATIC["static pool = 0.55 × free"]
        |  end
        |  FREE --> STATIC""".stripMargin
    )

  test("a group title in a nested group is not covered"):
    drawAndCheck(
      "nested",
      """flowchart TD
        |  subgraph OUTER["Outer group with a title that is long enough to wrap"]
        |    subgraph INNER["Inner group with a title that is long enough to wrap"]
        |      A["a node in the inner group"]
        |    end
        |    B["a node in the outer group"]
        |  end
        |  A --> B""".stripMargin
    )

  test("a short group title stays on one line and is not covered"):
    drawAndCheck(
      "one line",
      """flowchart TD
        |  subgraph POOL["Worker pool"]
        |    HOLD["pool hold = 52.6 GiB"]
        |  end
        |  HOLD --> Y""".stripMargin
    )
