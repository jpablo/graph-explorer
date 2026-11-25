package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.selection.{NodeElement, SelectableElement}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try

/** A click on a node of a Mermaid class diagram selects it.
  *
  * The bug: a click on a class node gave "NotFoundError: The object can not be
  * found here" (Safari) or "Failed to execute 'insertBefore' on 'Node'"
  * (Chromium), and the node did not show a selection. The diagram is the one
  * of the report. These tests use the real Mermaid renderer.
  *
  * The checks throw AssertionError, not munit's `assert`: through the Scala.js
  * test bridge and the sbt client, a munit FailException arrives with no message.
  */
class ClassDiagramSelectSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  private val BrowserUse =
    """classDiagram
      |    class AgentActor {
      |        -BrowserSubsystem browser
      |    }
      |
      |    class BrowserSubsystem {
      |        -Arc~BrowserService~ service
      |        -Option~BrowserHostGrant~ grant
      |    }
      |
      |    AgentActor "1" *-- "1" BrowserSubsystem : browser
      |    BrowserSubsystem "1" o-- "1" BrowserService : service
      |""".stripMargin

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then throw AssertionError(message)

  /** True when `a` comes before `b` in the document, so `a` paints below `b`. */
  private def before(a: dom.Node, b: dom.Node): Boolean =
    (a.compareDocumentPosition(b) & dom.Node.DOCUMENT_POSITION_FOLLOWING) != 0

  test("a click selects a class node: the selection rects go in, and the text paints above them"):
    val renderer = MermaidRenderer()
    renderer.textToSvg(BrowserUse).map: rendered =>
      val svg = rendered.svg.ref
      // getBBox needs a layout, thus the drawing must be in the document.
      dom.document.body.appendChild(svg)
      try
        val nodes = SelectableElement.findAll(svg, renderer.selectionStrategy).collect { case n: NodeElement => n }
        check(nodes.size == 3, s"expected 3 class nodes, found ${nodes.map(_.elementId)}")
        nodes.foreach: node =>
          val result = Try(node.select())
          check(result.isSuccess, s"select() of ${node.elementId} failed: ${result.failed.map(_.toString).getOrElse("")}")
          val rect = node.ref.querySelector(s"rect.${SelectableElement.selectionRectClass}")
          check(rect != null, s"${node.elementId} has no selection rect: ${node.ref.outerHTML.take(400)}")
          // The text of a class node is in g.label groups. The wash must not
          // cover the text, so each text group comes after the rect.
          val labels = node.ref.querySelectorAll("g.label")
          (0 until labels.length).map(labels(_)).foreach: label =>
            check(before(rect, label), s"the selection rect of ${node.elementId} paints over its text")
      finally svg.remove()
