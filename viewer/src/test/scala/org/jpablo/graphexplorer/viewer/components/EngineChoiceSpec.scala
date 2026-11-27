package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{div, render}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.components.attributes.views.DiagramAttributesViews
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The Engine row of the Diagram panel has the choice "Manual" (ADR 0004).
  *
  * "Manual" freezes the layout: each node keeps its place, and a drag moves it. A different engine removes the
  * stored positions, and the diagram uses that engine again. The source text does not get the word "manual".
  */
class EngineChoiceSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "EngineChoiceSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val dot     = "digraph G {\n  a -> b\n  a -> c\n}"
  private val mermaid = "flowchart TD\n  a --> b\n  a --> c\n"

  final class Panel(val state: ViewerState, val box: dom.html.Element):
    /** Each error message that the view showed. */
    val errors = scala.collection.mutable.ArrayBuffer.empty[String]
    state.errorBus.events.foreach(errors += _)(using state.owner)

    def engine: dom.html.Select =
      Option(box.querySelector(".gx-engine-select"))
        .getOrElse(fail(s"no Engine select in the panel: ${box.innerHTML.take(400)}"))
        .asInstanceOf[dom.html.Select]
    def optionTexts: List[String] =
      engine.querySelectorAll("option").toList.map(_.textContent)
    def text: String          = state.sourceText.now()
    def hasPositions: Boolean = state.layout.now().hasPositions

    /** Pick `value` in the Engine select, as a person does. */
    def pick(value: String): Unit =
      state.history.markGesture()
      engine.value = value
      engine.dispatchEvent(new dom.Event("change", js.Dynamic.literal(bubbles = true).asInstanceOf[dom.EventInit]))

  /** The panel of a diagram with a drawing of the node `a`. */
  private def withPanel(name: String, format: DiagramFormat, source: String)(body: Panel => Future[Unit]): Future[Unit] =
    withPanelWhen(name, format, source)(state =>
      Waits
        .settle(state.fullGraphNow().nodeIds.contains(NodeId("a")) && state.nodePlacesNow().nonEmpty, timeoutMs = 10000)
        .map(_ => check(state.nodePlacesNow().nonEmpty, "precondition: the diagram has no drawing"))
    )(body)

  /** The panel of a diagram after `ready`. */
  private def withPanelWhen(name: String, format: DiagramFormat, source: String)(ready: ViewerState => Future[Unit])(
      body: Panel => Future[Unit]
  ): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    dom.document.body.appendChild(box)
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val state = ViewerState(ViewTarget.diagram(name), graphviz, initialSource = Some(source))(using owner)
        ready(state)
          .flatMap: _ =>
            val root = render(box, div(DiagramAttributesViews.forFormat(format, state)))
            body(Panel(state, box)).transform(outcome => { root.unmount(); outcome })
      .transform: outcome =>
        box.remove()
        owner.killSubscriptions()
        outcome

  private def settled(condition: => Boolean): Future[Unit] = Waits.settle(condition, timeoutMs = 10000)

  test("the Engine row of a DOT diagram offers Manual, and an automatic diagram shows its engine"):
    withPanel("engine-dot-offer", DiagramFormat.DOT, dot): panel =>
      check(panel.optionTexts.contains("Manual"), s"no Manual choice: ${panel.optionTexts}")
      check(panel.optionTexts.contains("Hierarchical"), s"the engines are gone: ${panel.optionTexts}")
      check(panel.engine.value == "dot", s"an automatic diagram shows ${panel.engine.value}, not dot")
      Future.unit

  test("Manual freezes the layout, and the source does not change"):
    withPanel("engine-dot-manual", DiagramFormat.DOT, dot): panel =>
      val before = panel.text
      panel.pick("manual")
      settled(panel.hasPositions).map: _ =>
        check(panel.hasPositions, "Manual stored no positions")
        check(panel.text == before, s"Manual changed the source:\n${panel.text}")
        check(panel.engine.value == "manual", s"a frozen diagram shows ${panel.engine.value}, not manual")

  test("a different engine on a frozen diagram removes the positions and writes the engine"):
    withPanel("engine-dot-other", DiagramFormat.DOT, dot): panel =>
      panel.pick("manual")
      settled(panel.hasPositions).flatMap: _ =>
        panel.pick("circo")
        settled(!panel.hasPositions && panel.text.contains("circo")).map: _ =>
          check(!panel.hasPositions, "the positions stayed")
          check(raw"""layout\s*=\s*"?circo""".r.findFirstIn(panel.text).nonEmpty, s"no engine in the source:\n${panel.text}")
          check(panel.engine.value == "circo", s"the select shows ${panel.engine.value}, not circo")

  test("the same engine on a frozen diagram removes the positions, and the source does not change"):
    withPanel("engine-dot-same", DiagramFormat.DOT, dot): panel =>
      val before = panel.text
      panel.pick("manual")
      settled(panel.hasPositions).flatMap: _ =>
        panel.pick("dot")
        settled(!panel.hasPositions).map: _ =>
          check(!panel.hasPositions, "the positions stayed")
          check(panel.text == before, s"the source changed:\n${panel.text}")
          check(panel.engine.value == "dot", s"the select shows ${panel.engine.value}, not dot")

  test("one undo of a different engine brings back the positions and the old source"):
    withPanel("engine-dot-undo", DiagramFormat.DOT, dot): panel =>
      val before = panel.text
      panel.pick("manual")
      settled(panel.hasPositions).flatMap: _ =>
        val frozen = panel.state.layout.now()
        panel.pick("circo")
        settled(!panel.hasPositions && panel.text.contains("circo")).flatMap: _ =>
          panel.state.history.undo()
          settled(panel.hasPositions && panel.text == before).map: _ =>
            check(panel.state.layout.now() == frozen, s"one undo did not bring back the positions: ${panel.state.layout.now()}")
            check(panel.text == before, s"one undo did not bring back the source:\n${panel.text}")

  test("the Engine row of a Mermaid flowchart offers Automatic and Manual"):
    withPanel("engine-mermaid", DiagramFormat.Mermaid, mermaid): panel =>
      val before = panel.text
      check(panel.optionTexts == List("Automatic", "Manual"), s"the choices are ${panel.optionTexts}")
      check(panel.engine.value == "auto", s"an automatic flowchart shows ${panel.engine.value}")
      panel.pick("manual")
      settled(panel.hasPositions).flatMap: _ =>
        check(panel.text == before, s"Manual changed the source:\n${panel.text}")
        check(panel.engine.value == "manual", s"a frozen flowchart shows ${panel.engine.value}")
        panel.pick("auto")
        settled(!panel.hasPositions).map: _ =>
          check(panel.text == before, s"Automatic changed the source:\n${panel.text}")
          check(panel.engine.value == "auto", s"the select shows ${panel.engine.value}, not auto")

  // ------------------------------------------------------------ Manual is a mode
  //
  // Manual switches the diagram to the manual mode. It does not need a node or a drawing: an empty diagram, or a
  // diagram with no drawing yet, also goes to the manual mode. The nodes of a later edit then get their places in
  // that mode.

  private val emptyDot     = "digraph G {}"
  private val emptyMermaid = "flowchart TD\n"

  /** The diagram after its first render, with no node. */
  private def rendered(state: ViewerState): Future[Unit] =
    Waits.after(1500).map(_ => check(state.fullGraphNow().nodeIds.isEmpty, "precondition: the diagram has nodes"))

  test("Manual on an empty DOT diagram switches to the manual mode, with no error"):
    withPanelWhen("engine-empty-dot", DiagramFormat.DOT, emptyDot)(rendered): panel =>
      val before = panel.text
      panel.pick("manual")
      Waits.after(300).map: _ =>
        check(panel.errors.isEmpty, s"Manual showed an error: ${panel.errors.mkString(" | ")}")
        check(panel.engine.value == "manual", s"the select shows ${panel.engine.value}, not manual")
        check(panel.state.hasFreeLayoutNow, "the diagram is not in the manual mode")
        check(panel.text == before, s"Manual changed the source:\n${panel.text}")

  test("Manual before the first drawing switches to the manual mode, with no error"):
    withPanelWhen("engine-no-drawing", DiagramFormat.DOT, dot)(_ => Future.unit): panel =>
      panel.pick("manual")
      Waits.after(300).map: _ =>
        check(panel.errors.isEmpty, s"Manual showed an error: ${panel.errors.mkString(" | ")}")
        check(panel.engine.value == "manual", s"the select shows ${panel.engine.value}, not manual")
        check(panel.state.hasFreeLayoutNow, "the diagram is not in the manual mode")

  test("an empty diagram in the manual mode stays in it when a node comes"):
    withPanelWhen("engine-empty-then-node", DiagramFormat.DOT, emptyDot)(rendered): panel =>
      panel.pick("manual")
      Waits.after(300).flatMap: _ =>
        panel.state.history.markGesture()
        panel.state.sourceText.set("digraph G {\n  a\n}")
        settled(panel.state.fullGraphNow().nodeIds.contains(NodeId("a"))).flatMap(_ => Waits.after(1000)).map: _ =>
          check(panel.errors.isEmpty, s"an error: ${panel.errors.mkString(" | ")}")
          check(panel.engine.value == "manual", s"the new node ended the manual mode: ${panel.engine.value}")
          check(panel.state.hasFreeLayoutNow, "the new node ended the manual mode")

  test("Hierarchical on an empty diagram in the manual mode goes back to the automatic layout"):
    withPanelWhen("engine-empty-back", DiagramFormat.DOT, emptyDot)(rendered): panel =>
      val before = panel.text
      panel.pick("manual")
      Waits.after(300).flatMap: _ =>
        check(panel.engine.value == "manual", s"precondition: the select shows ${panel.engine.value}")
        panel.pick("dot")
        Waits.after(300).map: _ =>
          check(panel.engine.value == "dot", s"the select shows ${panel.engine.value}, not dot")
          check(!panel.state.hasFreeLayoutNow, "the diagram stayed in the manual mode")
          check(panel.text == before, s"the source changed:\n${panel.text}")

  test("one undo of Manual on an empty diagram goes back to the automatic layout"):
    withPanelWhen("engine-empty-undo", DiagramFormat.DOT, emptyDot)(rendered): panel =>
      panel.pick("manual")
      Waits.after(300).flatMap: _ =>
        check(panel.state.hasFreeLayoutNow, "precondition: no manual mode")
        panel.state.history.undo()
        Waits.after(300).map: _ =>
          check(!panel.state.hasFreeLayoutNow, "the undo kept the manual mode")
          check(panel.engine.value == "dot", s"the select shows ${panel.engine.value}, not dot")

  test("the manual mode of an empty diagram stays after a reload"):
    withPanelWhen("engine-empty-reload", DiagramFormat.DOT, emptyDot)(rendered): panel =>
      panel.pick("manual")
      Waits.after(500).flatMap: _ =>
        check(panel.state.hasFreeLayoutNow, "precondition: no manual mode")
        val owner = ManualOwner()
        TestHelpers.sharedGraphviz.flatMap: graphviz =>
          val again = ViewerState(ViewTarget.diagram("engine-empty-reload"), graphviz)(using owner)
          Waits.after(1500)
            .map(_ => check(again.hasFreeLayoutNow, "the reload lost the manual mode"))
            .transform(outcome => { owner.killSubscriptions(); outcome })

  test("Manual on an empty Mermaid flowchart switches to the manual mode, with no error"):
    withPanelWhen("engine-empty-mermaid", DiagramFormat.Mermaid, emptyMermaid)(_ => Waits.after(1500)): panel =>
      panel.pick("manual")
      Waits.after(300).map: _ =>
        check(panel.errors.isEmpty, s"Manual showed an error: ${panel.errors.mkString(" | ")}")
        check(panel.engine.value == "manual", s"the select shows ${panel.engine.value}, not manual")
        check(panel.state.hasFreeLayoutNow, "the flowchart is not in the manual mode")
