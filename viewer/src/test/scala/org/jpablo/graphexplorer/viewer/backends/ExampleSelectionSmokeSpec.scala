package org.jpablo.graphexplorer.viewer.backends

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.{BundledExampleSources, DotExamples, GraphvizRenderer}
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidRenderer
import org.jpablo.graphexplorer.viewer.components.selection.SelectableElement
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

/** A smoke test: each element of each bundled example can be selected and
  * unselected.
  *
  * Each kind of diagram that the app renders is also selectable: the Mermaid
  * rule for a node is `g.node` for each kind. The selection code changes the
  * DOM of the drawing, and each kind of diagram has its own DOM structure. A
  * click on a node of a class diagram threw `insertBefore` errors because of
  * this, and no test selected a class node. This test selects each element of
  * each example, so a new structure fails here, not at the first click.
  *
  * The checks throw AssertionError, not munit's `assert`: through the Scala.js
  * test bridge and the sbt client, a munit FailException arrives with no message.
  */
class ExampleSelectionSmokeSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  override def munitTimeout = scala.concurrent.duration.Duration(180, "s")

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then throw AssertionError(message)

  /** The problems of one example, or an empty list. */
  private def exercise(name: String, renderer: Renderer, source: String): Future[List[String]] =
    renderer.textToSvg(source).transform:
      case Failure(e) => Success(List(s"$name: the render failed: $e"))
      case Success(rendered) =>
        val svg = rendered.svg.ref
        // getBBox needs a layout, thus the drawing must be in the document.
        dom.document.body.appendChild(svg)
        try
          val elements = SelectableElement.findAll(svg, renderer.selectionStrategy)
          val problems =
            elements.toList.flatMap: element =>
              List("select" -> (() => element.select()), "unselect" -> (() => element.unselect()))
                .flatMap: (step, run) =>
                  Try(run()).failed.toOption.map(e => s"$name: $step of ${element.elementId}: $e")
          Success(problems)
        finally svg.remove()

  test("each element of each bundled example can be selected and unselected"):
    withGraphvizAsync: graphviz =>
      val dot     = GraphvizRenderer(graphviz)
      val mermaid = MermaidRenderer()
      val runs = DotExamples.examples.toList.map: (name, example) =>
        (name, example.format, BundledExampleSources.byPath.get(example.path))
      val missing = runs.collect { case (name, _, None) => name }
      check(missing.isEmpty, s"no bundled source for the examples: ${missing.mkString(", ")}")
      // One example after the other: Mermaid renders one diagram at a time.
      runs
        .foldLeft(Future.successful(List.empty[String])):
          case (done, (name, format, Some(source))) =>
            val renderer = if format == DiagramFormat.DOT then dot else mermaid
            done.flatMap(before => exercise(name, renderer, source).map(before ++ _))
          case (done, _) => done
        .map: problems =>
          check(problems.isEmpty, s"${problems.size} problems:\n${problems.mkString("\n")}")
