package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{div, render}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.components.attributes.views.{DiagramAboutView, DiagramAttributesViews}
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The About section at the end of the Diagram panel: the tags and the notes of the diagram.
  *
  * The tags and the notes are metadata, not text (`DiagramMetadata`). Thus they go through the
  * path of the hidden elements and the folded groups: `localStorage` for a browser diagram, and the
  * library entry for a file (`FollowMetadataSpec`).
  */
class DiagramAboutSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "DiagramAboutSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val diagramId = "about-spec"
  private val dot       = "digraph G { a }"
  private val mermaid   = "flowchart TD\n  a --> b"

  final class Panel(val state: ViewerState, val box: dom.html.Element):
    def find(selector: String): Option[dom.html.Element] =
      Option(box.querySelector(selector)).map(_.asInstanceOf[dom.html.Element])
    def about: Option[dom.html.Element]  = find("#diagram-about")
    def tagInput: dom.html.Input         = find("#diagram-about .gx-tag-input").getOrElse(fail("no tag input")).asInstanceOf[dom.html.Input]
    def notesBox: dom.html.TextArea      = find("#diagram-about .gx-notes").getOrElse(fail("no notes box")).asInstanceOf[dom.html.TextArea]
    def chips: List[String] =
      box.querySelectorAll("#diagram-about .gx-tag .gx-tag-text").toList.map(_.textContent)
    def removeButton(tag: String): Option[dom.html.Element] = find(s"#diagram-about [aria-label='Remove the tag $tag']")

    /** Type `text` into the tag box, and press Enter. */
    def typeTag(text: String): Unit =
      tagInput.value = text
      tagInput.dispatchEvent(new dom.Event("input", js.Dynamic.literal(bubbles = true).asInstanceOf[dom.EventInit]))
      val init = js.Dynamic.literal(key = "Enter", bubbles = true, cancelable = true)
      tagInput.dispatchEvent(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))

    /** Type `text` into the notes box. The box does not save yet. */
    def typeNotes(text: String): Unit =
      notesBox.value = text
      notesBox.dispatchEvent(new dom.Event("input", js.Dynamic.literal(bubbles = true).asInstanceOf[dom.EventInit]))

  private def withPanel(format: DiagramFormat = DiagramFormat.DOT, source: String = dot)(body: Panel => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    dom.document.body.appendChild(box)
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val state = ViewerState(ViewTarget.diagram(diagramId), graphviz, initialSource = Some(source))(using owner)
        Waits
          .settle(state.fullGraphNow().nodeIds.contains(NodeId("a")), timeoutMs = 10000)
          .flatMap: _ =>
            check(state.fullGraphNow().nodeIds.contains(NodeId("a")), "precondition: the diagram did not load")
            val root = render(box, div(DiagramAttributesViews.forFormat(format, state)))
            body(Panel(state, box)).transform(outcome => { root.unmount(); outcome })
      .transform: outcome =>
        box.remove()
        owner.killSubscriptions()
        outcome

  /** A new viewer of the same browser diagram: what a reload of the page shows. */
  private def reloaded(): Future[ViewerState] =
    val owner = ManualOwner()
    TestHelpers.sharedGraphviz.flatMap: graphviz =>
      val state = ViewerState(ViewTarget.diagram(diagramId), graphviz)(using owner)
      afterMicrotasks(state).transform(outcome => { owner.killSubscriptions(); outcome })

  // ---- tags -----------------------------------------------------------------------------

  test("the Diagram panel of DOT ends with the About section"):
    withPanel(): p =>
      Future:
        check(p.about.isDefined, s"no About section: ${p.box.innerHTML.take(400)}")
        check(p.about.exists(_.textContent.contains("About")), "the section has no heading About")

  test("the Diagram panel of Mermaid ends with the About section"):
    withPanel(DiagramFormat.Mermaid, mermaid): p =>
      Future(check(p.about.isDefined, s"no About section: ${p.box.innerHTML.take(400)}"))

  test("Enter adds the trimmed tag as a chip, clears the box, and the tag reaches the metadata"):
    withPanel(): p =>
      p.typeTag("  infra ")
      Waits.settle(p.chips.nonEmpty).map: _ =>
        same(p.state.metadata.now().tags, List("infra"), "the tag did not reach the metadata")
        same(p.chips, List("infra"), "the chip")
        same(p.tagInput.value, "", "the box did not clear")

  test("an empty tag and a tag that the diagram has add nothing"):
    withPanel(): p =>
      p.typeTag("infra")
      p.typeTag("   ")
      p.typeTag("infra ")
      Waits.after(50).map: _ =>
        same(p.state.metadata.now().tags, List("infra"))
        same(p.chips, List("infra"))

  test("a click on the x of a chip removes the tag"):
    withPanel(): p =>
      p.typeTag("a")
      p.typeTag("b")
      Waits
        .settle(p.removeButton("a").isDefined)
        .flatMap: _ =>
          p.removeButton("a").get.click()
          Waits.settle(p.chips == List("b"))
        .map: _ =>
          same(p.state.metadata.now().tags, List("b"), "the tag did not go from the metadata")

  // ---- notes ----------------------------------------------------------------------------

  test("a note saves when the box loses the focus"):
    withPanel(): p =>
      p.notesBox.focus()
      p.typeNotes("Generated by the build.")
      same(p.state.metadata.now().notes, "", "the note saved before the pause or the blur")
      p.notesBox.blur()
      Waits.after(20).map: _ =>
        same(p.state.metadata.now().notes, "Generated by the build.", "the blur did not save the note")

  test("a note saves after a short pause"):
    withPanel(): p =>
      p.notesBox.focus()
      p.typeNotes("Line 1\nLine 2")
      Waits.after(DiagramAboutView.NotesPauseMs + 200).map: _ =>
        same(p.state.metadata.now().notes, "Line 1\nLine 2", "the pause did not save the note")
        same(p.notesBox.value, "Line 1\nLine 2", "the save changed the text in the box")

  test("the notes box shows the note of the metadata"):
    withPanel(): p =>
      p.state.metadata.notes.set("from gx")
      Waits.settle(p.notesBox.value == "from gx").map(_ => same(p.notesBox.value, "from gx"))

  // ---- a reload -------------------------------------------------------------------------

  test("the tags and the note of a browser diagram survive a reload"):
    withPanel(): p =>
      p.typeTag("infra")
      p.typeTag("draft")
      p.notesBox.focus()
      p.typeNotes("Keep this.")
      p.notesBox.blur()
      Waits
        .after(20)
        .flatMap(_ => reloaded())
        .map: again =>
          same(again.metadata.now().tags, List("infra", "draft"), "the reload lost the tags")
          same(again.metadata.now().notes, "Keep this.", "the reload lost the note")
