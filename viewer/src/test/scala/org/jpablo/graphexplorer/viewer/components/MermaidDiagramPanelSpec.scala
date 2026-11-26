package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{div, render}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.{MermaidBackgroundAttr, MermaidFontFamilyAttr}
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidInitDirective
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.attributes.views.MermaidDiagramAttributesView
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Missing, Single}
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** The global properties in the Mermaid Diagram panel change the source text.
  *
  * The panel has rows for the theme, the font, the font size and the
  * background. A change in a row writes the init directive
  * (`%%{init: {...}}%%`) at the top of the source. The graph comes from real
  * Mermaid (`MermaidExtractor`), as in the app.
  */
class MermaidDiagramPanelSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "MermaidDiagramPanelSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val source = "flowchart LR\n  api[API] --> db[DB]\n"

  final class Panel(val state: ViewerState, val box: dom.html.Element):
    def find(selector: String): Option[dom.html.Element] =
      Option(box.querySelector(selector)).map(_.asInstanceOf[dom.html.Element])
    def text: String = state.sourceText.now()
    def config: Option[ujson.Obj] =
      MermaidInitDirective.configOf(text).map(ujson.read(_).obj).map(ujson.Obj.from)
    def variable(key: String): Option[String] =
      config.flatMap(_.value.get("themeVariables")).flatMap(_.objOpt).flatMap(_.get(key)).flatMap(_.strOpt)

  private def withPanel(text: String)(body: Panel => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    dom.document.body.appendChild(box)
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val state = ViewerState(ViewTarget.diagram("mermaid-panel-spec"), graphviz, initialSource = Some(text))(using owner)
        def parsed =
          state.loadStatus.now() == DiagramLoadStatus.Ready && state.fullGraphNow().nodeIds.contains(NodeId("api"))
        Waits
          .settle(parsed, timeoutMs = 10000)
          .flatMap: _ =>
            check(parsed, s"precondition: the Mermaid parse did not end. The status is ${state.loadStatus.now()}")
            val root = render(box, div(MermaidDiagramAttributesView(state)))
            body(Panel(state, box)).transform(outcome => { root.unmount(); outcome })
      .transform: outcome =>
        box.remove()
        owner.killSubscriptions()
        outcome

  test("the panel has the rows Theme, Font, Font size and Background"):
    withPanel(source): p =>
      Future:
        val shown = p.box.textContent
        for name <- List("Theme", "Font", "Font size", "Background") do
          check(shown.contains(name), s"the panel has no row $name: ${shown.take(400)}")
        check(p.find("select.cls-mermaid_theme").isDefined, s"the panel has no theme select: ${p.box.innerHTML.take(600)}")

  test("a change of the theme in the panel writes the theme in the source"):
    withPanel(source): p =>
      val select = p.find("select.cls-mermaid_theme").map(_.asInstanceOf[dom.html.Select])
      check(select.isDefined, s"the panel has no theme select: ${p.box.innerHTML.take(600)}")
      val options = (0 until select.get.options.length).map(i => select.get.options(i).value).toList
      same(options, MermaidInitDirective.Themes.toList, "the theme select has the wrong options")
      select.get.value = "forest"
      select.get.dispatchEvent(dom.Event("change", new dom.EventInit { bubbles = true }))
      Waits.settle(p.text.startsWith("%%{init: ")).map: _ =>
        check(p.text.startsWith("%%{init: "), s"the source has no directive:\n${p.text}")
        same(p.config.flatMap(_.value.get("theme")).flatMap(_.strOpt), Some("forest"), s"the theme in the source:\n${p.text}")
        check(p.text.contains("api"), s"the source lost the diagram:\n${p.text}")

  test("a change of the font size in the panel writes the size in px"):
    withPanel(source): p =>
      val slider = p.find("input[type=range]").map(_.asInstanceOf[dom.html.Input])
      check(slider.isDefined, s"the panel has no font-size slider: ${p.box.innerHTML.take(600)}")
      slider.get.value = "22"
      slider.get.dispatchEvent(dom.Event("input", new dom.EventInit { bubbles = true }))
      Waits.settle(p.variable("fontSize").isDefined).map: _ =>
        same(p.variable("fontSize"), Some("22px"), s"the font size in the source:\n${p.text}")

  test("the background and the font go to the directive, and a reset removes them"):
    withPanel(source): p =>
      RowBuilder.simpleInputVar(MermaidBackgroundAttr, p.state.diagramAttributesUpdates).set(Single(AttrValue("#fef3c7")))
      RowBuilder.simpleInputVar(MermaidFontFamilyAttr, p.state.diagramAttributesUpdates).set(Single(AttrValue("Georgia")))
      Waits
        .settle(p.variable("background").isDefined && p.variable("fontFamily").isDefined)
        .flatMap: _ =>
          same(p.variable("background"), Some("#fef3c7"), s"the background in the source:\n${p.text}")
          same(p.variable("fontFamily"), Some("Georgia"), s"the font in the source:\n${p.text}")
          RowBuilder.simpleInputVar(MermaidBackgroundAttr, p.state.diagramAttributesUpdates).set(Missing)
          RowBuilder.simpleInputVar(MermaidFontFamilyAttr, p.state.diagramAttributesUpdates).set(Missing)
          Waits.settle(!p.text.contains("%%{"))
        .map: _ =>
          check(!p.text.contains("%%{"), s"the reset left a directive:\n${p.text}")

  test("a change in the panel keeps the other keys of the directive"):
    val withDirective = """%%{init: {"flowchart": {"curve": "basis"}, "theme": "dark"}}%%""" + "\n" + source
    withPanel(withDirective): p =>
      RowBuilder.simpleInputVar(MermaidFontFamilyAttr, p.state.diagramAttributesUpdates).set(Single(AttrValue("Verdana")))
      Waits.settle(p.variable("fontFamily").isDefined).map: _ =>
        same(p.variable("fontFamily"), Some("Verdana"), s"the font in the source:\n${p.text}")
        same(p.config.flatMap(_.value.get("theme")).flatMap(_.strOpt), Some("dark"), s"the theme is lost:\n${p.text}")
        check(p.config.exists(_.value.contains("flowchart")), s"the flowchart key is lost:\n${p.text}")
