package org.jpablo.graphexplorer.viewer.components

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.{div, render, Signal}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.toRawText
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.components.labelEditor.FormattedEditor
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, XLabel}
import org.jpablo.graphexplorer.viewer.models.{ArrowId, AttributeId, ElementId, ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.{ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The external label (`xlabel`) of a node or an arrow has the label editor.
  *
  * Graphviz draws an `xlabel` outside the element, and it can be HTML-like markup, as a label can. The XLabel row of
  * the "More attributes" card of the context strip is a one-line text box. For one selected element with a label that
  * is markup, the row shows the label as the canvas draws it, in a button that opens the dialog. The row has a button
  * "Edit the external label". The button opens the Edit External Label dialog, with the tabs "Formatted" and "Source" and a preview. The
  * preview draws the label as text with the font of the element, and with no shape, as the canvas draws it.
  */
class EditXLabelDialogSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "EditXLabelDialogSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val a   = NodeId("a")
  private val dot = """digraph G { a [label="node a", xlabel=<<B>ext</B>>]; a -> b [xlabel="plain x"] }"""

  final class Strip(val state: ViewerState, val box: dom.html.Element):
    def find(selector: String): Option[dom.html.Element] =
      Option(box.querySelector(selector)).map(_.asInstanceOf[dom.html.Element])
    def button: Option[dom.html.Element] = find("#selection-toolbar [aria-label='Edit the external label']")
    def dialogOpen: Boolean              = find("#edit-xlabel-dialog dialog").isDefined
    def prose: Option[dom.html.Element]  = find("#edit-xlabel-dialog .ProseMirror")
    def preview: Option[dom.html.Element] = find("#edit-xlabel-dialog .gx-label-preview svg")
    /** The values of the text boxes of the "More attributes" card of the strip (`card`: nodes or arrows). */
    def boxValues(card: String): Seq[String] =
      find(s"#$card").toSeq.flatMap: c =>
        c.querySelectorAll("input, textarea").toSeq.collect:
          case i: dom.html.Input    => i.value
          case t: dom.html.TextArea => t.value
    /** The texts of the drawings (svg) in the "More attributes" card of the strip. */
    def drawnTexts(card: String): Seq[String] =
      find(s"#$card").toSeq.flatMap(_.querySelectorAll("svg text").toSeq.map(_.textContent.trim))
    def attr(id: ElementId, name: AttributeId): Option[String] =
      val g = state.fullGraphNow()
      val attrs = id match
        case n: NodeId  => g.nodes.get(n).map(_.attributes)
        case r: ArrowId => g.arrows.get(r).map(_.attributes)
        case _          => None
      attrs.flatMap(_.get(name)).map(_.toString)

  private def withStrip(body: Strip => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    dom.document.body.appendChild(box)
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val state = ViewerState(ViewTarget.diagram("edit-xlabel-spec"), graphviz, initialSource = Some(dot))(using owner)
        Waits
          .settle(state.fullGraphNow().nodeIds.contains(a), timeoutMs = 10000)
          .flatMap: _ =>
            check(state.fullGraphNow().nodeIds.contains(a), "precondition: the diagram did not load")
            val commands = Commands(state, RouterCommands(Router()))
            val root     = render(box, div(AttributesToolbar(Signal.fromValue("x"), commands, state), EditXLabelDialog(state)))
            body(Strip(state, box)).transform(outcome => { root.unmount(); outcome })
      .transform: outcome =>
        box.remove()
        owner.killSubscriptions()
        outcome

  /** Select `id`, click the button of the XLabel row, and wait for the formatted editor. */
  private def open(s: Strip, id: ElementId): Future[Unit] =
    s.state.selection.set(ElementIds(Set(id)))
    Waits
      .settle(s.button.isDefined)
      .flatMap: _ =>
        check(s.button.isDefined, s"the XLabel row has no button 'Edit the external label': ${s.box.innerHTML.take(500)}")
        s.button.get.click()
        Waits.settle(FormattedEditor.viewOf(s.box).isDefined)
      .map: _ =>
        check(s.dialogOpen, "the button did not open the dialog")
        check(FormattedEditor.viewOf(s.box).isDefined, "the dialog has no formatted editor")

  private def typeAndEnter(s: Strip, text: String): Unit =
    val view = FormattedEditor.viewOf(s.box).get
    view.dispatch(view.state.tr.insertText(text, view.state.doc.content.size - 1))
    val init = js.Dynamic.literal(key = "Enter", bubbles = true, cancelable = true)
    view.dom.dispatchEvent(new dom.KeyboardEvent("keydown", init.asInstanceOf[dom.KeyboardEventInit]))

  test("the button of the XLabel row of a node opens the external label in Formatted"):
    withStrip: s =>
      open(s, a).map: _ =>
        check(s.prose.exists(_.textContent == "ext"), s"the editor shows '${s.prose.map(_.textContent)}'")
        check(s.prose.exists(_.querySelector("b") != null), "ext is not bold")

  test("an edit and Enter save the external label of the node, and the label does not change"):
    withStrip: s =>
      open(s, a).map: _ =>
        typeAndEnter(s, "!")
        check(s.attr(a, XLabel.attrId).exists(_.contains("<B>ext!</B>")), s"the xlabel is ${s.attr(a, XLabel.attrId)}")
        check(s.attr(a, Label.attrId).exists(_.contains("node a")), s"the label is ${s.attr(a, Label.attrId)}")
        check(!s.dialogOpen, "the dialog is still open")

  test("the external label of an arrow opens and saves too"):
    withStrip: s =>
      val arrow = s.state.fullGraphNow().arrows.keys.head
      open(s, arrow).map: _ =>
        check(s.prose.exists(_.textContent == "plain x"), s"the editor shows '${s.prose.map(_.textContent)}'")
        typeAndEnter(s, "y")
        check(s.attr(arrow, XLabel.attrId).exists(_.contains("plain xy")), s"the xlabel is ${s.attr(arrow, XLabel.attrId)}")

  test("the preview draws the external label as text, with no shape"):
    withStrip: s =>
      open(s, a)
        .flatMap(_ => Waits.settle(s.preview.exists(_.textContent.contains("ext"))))
        .map: _ =>
          check(s.preview.exists(_.textContent.contains("ext")), s"the preview shows '${s.preview.map(_.textContent)}'")
          val shapes = s.preview.toList.flatMap(_.querySelectorAll("g.node path, g.node polygon, g.node ellipse").toList)
          check(shapes.isEmpty, s"the preview draws a shape: ${shapes.map(_.outerHTML.take(80))}")

  // ---- the XLabel row shows an HTML external label as the canvas draws it -------------------
  // Found on 2026-11-28. The XLabel row of the "More attributes" card showed the markup `abc<BR/><I>def</I>` as text.
  // The Title row of the Diagram panel shows the title drawn, and a click opens its dialog. The XLabel row does the
  // same for an external label with markup.

  private val htmlDot =
    """digraph G { a [xlabel=<abc<BR/><I>def</I>>]; b [xlabel="plain b"]; a -> b [xlabel=<x<BR/><B>y</B>>] }"""

  private def withHtmlStrip(body: Strip => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    val box   = dom.document.createElement("div").asInstanceOf[dom.html.Element]
    dom.document.body.appendChild(box)
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        val state = ViewerState(ViewTarget.diagram("xlabel-row-spec"), graphviz, initialSource = Some(htmlDot))(using owner)
        Waits
          .settle(state.fullGraphNow().nodeIds.contains(a), timeoutMs = 10000)
          .flatMap: _ =>
            check(state.fullGraphNow().nodeIds.contains(a), "precondition: the diagram did not load")
            val commands = Commands(state, RouterCommands(Router()))
            val root     = render(box, div(AttributesToolbar(Signal.fromValue("x"), commands, state), EditXLabelDialog(state)))
            body(Strip(state, box)).transform(outcome => { root.unmount(); outcome })
      .transform: outcome =>
        box.remove()
        owner.killSubscriptions()
        outcome

  /** The XLabel row of `card` draws `lines`, and no text box of the card shows the markup. */
  private def rowDraws(s: Strip, card: String, lines: Seq[String], markup: String): Future[Unit] =
    Waits
      .settle(lines.forall(s.drawnTexts(card).contains))
      .map: _ =>
        val boxes = s.boxValues(card)
        check(!boxes.exists(_.contains(markup)), s"the XLabel row shows the markup as text: ${boxes.mkString(" | ")}")
        check(
          lines.forall(s.drawnTexts(card).contains),
          s"the XLabel row does not draw the lines ${lines.mkString(", ")}: ${s.drawnTexts(card).mkString(" | ")}"
        )

  test("the XLabel row of a node draws an external label with markup, not its markup"):
    withHtmlStrip: s =>
      s.state.selection.set(ElementIds(Set(a)))
      rowDraws(s, "extra-node-attributes", Seq("abc", "def"), "<BR/>")

  test("the XLabel row of an arrow draws an external label with markup, not its markup"):
    withHtmlStrip: s =>
      val arrow = s.state.fullGraphNow().arrows.keys.head
      s.state.selection.set(ElementIds(Set(arrow)))
      rowDraws(s, "extra-arrow-attributes", Seq("x", "y"), "<BR/>")

  test("a click on the drawn external label in the XLabel row opens the dialog"):
    withHtmlStrip: s =>
      s.state.selection.set(ElementIds(Set(a)))
      Waits
        .settle(s.find("#extra-node-attributes svg").isDefined)
        .flatMap: _ =>
          val drawn = s.find("#extra-node-attributes svg").map(_.closest("button, [role='button']"))
          check(drawn.exists(_ != null), s"the drawn external label is not a button: ${s.find("#extra-node-attributes").map(_.innerHTML.take(600))}")
          drawn.get.asInstanceOf[dom.html.Element].click()
          Waits.settle(FormattedEditor.viewOf(s.box).isDefined)
        .map(_ => check(s.dialogOpen, "a click on the drawn external label did not open the dialog"))

  test("a plain external label keeps the text box of the XLabel row"):
    withHtmlStrip: s =>
      s.state.selection.set(ElementIds(Set(NodeId("b"))))
      Waits
        .settle(s.boxValues("extra-node-attributes").contains("plain b"))
        .map: _ =>
          check(
            s.boxValues("extra-node-attributes").contains("plain b"),
            s"the XLabel row of a plain external label has no text box with it: ${s.boxValues("extra-node-attributes")}"
          )

  test("the XLabel row changes between the text box and the drawing when the label changes between plain and markup"):
    withHtmlStrip: s =>
      val b    = NodeId("b")
      val card = "extra-node-attributes"
      s.state.selection.set(ElementIds(Set(b)))
      Waits
        .settle(s.boxValues(card).contains("plain b"))
        .flatMap: _ =>
          // Type markup in the text box of the row.
          val input = s.find(s"#$card").toSeq
            .flatMap(_.querySelectorAll("input").toSeq.collect { case i: dom.html.Input if i.value == "plain b" => i })
            .headOption
          check(input.isDefined, s"the XLabel row has no text box with 'plain b': ${s.boxValues(card)}")
          input.get.value = "q<BR/><I>r</I>"
          input.get.dispatchEvent(new dom.Event("input", js.Dynamic.literal(bubbles = true).asInstanceOf[dom.EventInit]))
          rowDraws(s, card, Seq("q", "r"), "<BR/>")
        .flatMap: _ =>
          // A plain label again gives the text box again.
          val updates = s.state.elementAttributesUpdates(ElementIds(Set(b)))
          toRawText(RowBuilder.simpleInputVar(XLabel.attrId, updates), "").set("plain again")
          Waits.settle(s.boxValues(card).contains("plain again"))
        .map: _ =>
          check(s.boxValues(card).contains("plain again"), s"the XLabel row has no text box after a plain label: ${s.boxValues(card)}")
          check(!s.drawnTexts(card).contains("r"), s"the XLabel row still draws the markup: ${s.drawnTexts(card)}")

  // Found on 2026-11-28. Markup that the person types in the text box changed the row to the drawing after the key
  // that completed a tag. The text box then left the page, and the next keys went nowhere. While the text box has
  // the focus, the row keeps it. When the focus leaves, the row draws the label.
  test("the XLabel row keeps its text box and the focus while the person types markup, and draws it after the blur"):
    withHtmlStrip: s =>
      val b    = NodeId("b")
      val card = "extra-node-attributes"
      s.state.selection.set(ElementIds(Set(b)))
      Waits
        .settle(s.boxValues(card).contains("plain b"))
        .flatMap: _ =>
          val input = s.find(s"#$card").toSeq
            .flatMap(_.querySelectorAll("input").toSeq.collect { case i: dom.html.Input if i.value == "plain b" => i })
            .headOption
          check(input.isDefined, s"the XLabel row has no text box with 'plain b': ${s.boxValues(card)}")
          val box = input.get
          box.focus()
          check(dom.document.activeElement eq box, "precondition: the text box does not take the focus")
          // One input event for each key, as a browser sends them.
          val markup = "q<BR/><I>r</I>"
          box.value = ""
          for n <- 1 to markup.length do
            box.value = markup.take(n)
            box.dispatchEvent(new dom.Event("input", js.Dynamic.literal(bubbles = true).asInstanceOf[dom.EventInit]))
          Waits.after(100).map(_ => box)
        .flatMap: box =>
          check(box.isConnected, s"the text box left the page during the typing: the row shows ${s.drawnTexts(card)}")
          check(dom.document.activeElement eq box, "the text box lost the focus during the typing")
          check(box.value == "q<BR/><I>r</I>", s"the text box shows '${box.value}'")
          box.blur()
          rowDraws(s, card, Seq("q", "r"), "<BR/>")

end EditXLabelDialogSpec
