package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Label
import org.jpablo.graphexplorer.viewer.graph.ImageNode
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, ViewTarget, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, TimerHelpers}
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** ⌘V on the canvas puts the content of the clipboard on the canvas.
  *
  *   - An image (a screenshot) becomes a new node that shows the image.
  *   - Text that declares a DOT or a Mermaid diagram replaces the diagram, as before.
  *   - Other text becomes a new node, with the text as its label.
  *
  * Each test sends a real `paste` event, with a `DataTransfer`, to the canvas container. The browser gives the
  * clipboard to the page in this event, so the test goes through the same path as a paste of the person.
  */
class CanvasPasteSpec extends FunSuite with TestHelpers with TimerHelpers:
  import CanvasPasteSpec.*

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[CanvasPasteSpec] $clue")

  override def munitFixtures = List(mockStorageFixture())

  /** A viewer of `source` in a canvas in the document, and the messages that it gives. */
  private final class Canvas(val state: ViewerState, val box: dom.html.Element):
    val errors = mutable.Buffer.empty[String]
    val infos  = mutable.Buffer.empty[String]

    def container: dom.html.Element = box.querySelector("#canvas-container").asInstanceOf[dom.html.Element]
    def svg: Option[dom.Element]    = Option(container.querySelector(":scope > svg"))
    def source: String              = state.sourceText.now()
    def nodeIds: Set[NodeId]        = state.fullGraphNow().nodeIds.toSet

    /** The `image` elements of the drawing, with their links. */
    def images: Seq[(dom.Element, String)] =
      svg.toSeq.flatMap(_.querySelectorAll("image").toSeq).map(el => el -> hrefOf(el))

    /** The link of the `image` element in the group of the node `id`, as Graphviz draws it. */
    def imageOfNode(id: String): Option[String] =
      svg.toSeq
        .flatMap(_.querySelectorAll("g.node").toSeq)
        .find(_.id == NodeId(id).toSvg)
        .flatMap(g => Option(g.querySelector("image")))
        .map(hrefOf)

    def paste(transfer: js.Dynamic): Unit =
      val init = js.Dynamic.literal(clipboardData = transfer, bubbles = true, cancelable = true)
      val ev   = js.Dynamic.newInstance(js.Dynamic.global.ClipboardEvent)("paste", init).asInstanceOf[dom.Event]
      container.dispatchEvent(ev)

  private def withCanvas(name: String, source: String)(body: Canvas => Future[Unit]): Future[Unit] =
    val owner    = ManualOwner()
    val errorBus = EventBus[String]()
    val infoBus  = EventBus[String]()
    withGraphvizAsync: graphviz =>
      val state =
        ViewerState(ViewTarget.diagram(name), graphviz, initialSource = Some(source), errorBus = errorBus, infoBus = infoBus)(
          using owner
        )
      val commands = Commands(state, RouterCommands(Router()))
      val box      = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      box.style.width = "800px"
      box.style.height = "600px"
      dom.document.body.appendChild(box)
      val root   = render(box, CanvasContainer(state, commands))
      val canvas = Canvas(state, box)
      errorBus.events.foreach(canvas.errors += _)(using owner)
      infoBus.events.foreach(canvas.infos += _)(using owner)
      waitFor(s"the first drawing of $name")(canvas.svg.isDefined && state.loadStatus.now() != DiagramLoadStatus.Loading)
        .flatMap(_ => body(canvas))
        .transform: outcome =>
          root.unmount()
          box.parentNode.removeChild(box)
          owner.killSubscriptions()
          outcome

  private val dotSource     = "digraph G {\n  a -> b\n}"
  private val mermaidSource = "flowchart TD\n  a --> b"

  // ------------------------------------------------------------------ text

  test("DOT: a paste of plain text adds a node with the text as its label"):
    withCanvas("paste-text-dot", dotSource): c =>
      c.paste(textTransfer("Hello world"))
      for
        _ <- waitFor(s"the new node, source:\n${c.source}")(c.nodeIds.contains(NodeId("hello_world")))
        _ = check(c.source.contains(""""hello_world" [label="Hello world"]"""), s"the source has no text node:\n${c.source}")
        _ = check(c.nodeIds == Set(NodeId("a"), NodeId("b"), NodeId("hello_world")), s"the nodes: ${c.nodeIds}")
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  test("Mermaid: a paste of text with quotes and a line break keeps the text through the real Mermaid parser"):
    val text = "say \"hi\"; a # b\nsecond line"
    withCanvas("paste-text-mermaid", mermaidSource): c =>
      c.paste(textTransfer(text))
      for
        _ <- waitFor(s"the new node, source:\n${c.source}")(c.nodeIds.contains(NodeId("say_hi_a")))
        // The text goes through the Mermaid parser of the library after the canvas edit.
        _ <- waitFor("the parse of the new text")(c.state.loadStatus.now() == DiagramLoadStatus.Ready)
        _ = check(c.source.startsWith("flowchart"), s"the diagram is not Mermaid any more:\n${c.source}")
        label = c.state.fullGraphNow().nodes(NodeId("say_hi_a")).attributes.get(Label.attrId).map(_.toString)
        _ = check(label.contains("say \"hi\"; a # b\\nsecond line"), s"the label: $label\n${c.source}")
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  test("a paste of text that declares a diagram still replaces the diagram"):
    val pasted = "digraph H {\n  x -> y\n}"
    withCanvas("paste-declared", mermaidSource): c =>
      c.paste(textTransfer(pasted))
      for
        _ <- waitFor(s"the new diagram, source:\n${c.source}")(c.nodeIds == Set(NodeId("x"), NodeId("y")))
        _ = check(c.source == pasted, s"the source:\n${c.source}")
        _ = check(c.state.formatSelection.now() == DiagramFormat.DOT, "the language did not follow the paste")
      yield ()

  test("a paste with no text and no image changes nothing, and the person gets a message"):
    withCanvas("paste-empty", dotSource): c =>
      c.paste(textTransfer("   \n "))
      afterMicrotasks:
        check(c.source == dotSource, s"the source:\n${c.source}")
        check(c.infos.nonEmpty, "no message")

  // ------------------------------------------------------------------ image

  test("DOT: a paste of a PNG image adds an image node, and Graphviz draws the image"):
    withCanvas("paste-image-dot", dotSource): c =>
      c.paste(imageTransfer(Png2x1Base64))
      for
        _ <- waitFor(s"the image node, source:\n${c.source}")(c.nodeIds.contains(NodeId("image1")))
        _ = check(c.source.contains(s"""image="$Png2x1""""), s"the source has no image:\n${c.source}")
        spec = ImageNode.of(c.state.fullGraphNow().nodes(NodeId("image1")).attributes)
        _ = check(spec.contains(ImageNode.Spec(Png2x1, Some((2, 1)), "")), s"the image node: $spec")
        _ <- waitFor("the image in the drawing")(c.imageOfNode("image1").isDefined)
        _ = check(c.imageOfNode("image1").contains(Png2x1), s"the drawing: ${c.svg.map(_.outerHTML.take(3000))}")
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  test("Mermaid: a paste of a PNG image adds an image shape, and Mermaid draws the image"):
    withCanvas("paste-image-mermaid", mermaidSource): c =>
      c.paste(imageTransfer(Png2x1Base64))
      for
        _ <- waitFor(s"the image node, source:\n${c.source}")(c.source.contains("image1@{"))
        _ = check(
              c.source.contains(s"""image1@{ img: "$Png2x1", w: 2, h: 1, constraint: "on" }"""),
              s"the source has no image shape:\n${c.source}"
            )
        _ <- waitFor(s"the parse of the image shape, errors: ${c.errors}")(
               c.state.loadStatus.now() == DiagramLoadStatus.Ready && c.nodeIds.contains(NodeId("image1"))
             )
        spec = ImageNode.of(c.state.fullGraphNow().nodes(NodeId("image1")).attributes)
        _ = check(spec.contains(ImageNode.Spec(Png2x1, Some((2, 1)), "")), s"the image node after the parse: $spec")
        _ <- waitFor(s"the image in the drawing: ${c.svg.map(_.outerHTML.take(3000))}")(c.images.exists(_._2 == Png2x1))
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  test("Mermaid: a canvas edit after the paste keeps the image node"):
    withCanvas("paste-image-mermaid-edit", mermaidSource): c =>
      c.paste(imageTransfer(Png2x1Base64))
      for
        _ <- waitFor("the parse of the image shape")(
               c.state.loadStatus.now() == DiagramLoadStatus.Ready && c.nodeIds.contains(NodeId("image1"))
             )
        before = c.source
        _ = c.state.addArrow(NodeId("a"), NodeId("image1"))
        _ <- waitFor(s"the arrow, source:\n${c.source}")(c.source.contains("a --> image1"))
        _ = check(c.source.contains(s"""image1@{ img: "$Png2x1""""), s"the image shape is gone:\n${c.source}")
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}\nbefore:\n$before")
      yield ()

  test("an image on the clipboard wins over the text on the clipboard"):
    withCanvas("paste-image-and-text", dotSource): c =>
      val transfer = imageTransfer(Png2x1Base64)
      transfer.setData("text/plain", "shot.png")
      c.paste(transfer)
      for
        _ <- waitFor(s"the image node, source:\n${c.source}")(c.nodeIds.contains(NodeId("image1")))
        _ = check(!c.nodeIds.contains(NodeId("shot_png")), s"the text also became a node: ${c.nodeIds}")
      yield ()

  // ------------------------------------------------------------------ undo and refusals

  test("one undo removes the pasted node"):
    withCanvas("paste-undo", dotSource): c =>
      c.paste(textTransfer("Hello world"))
      for
        _ <- waitFor("the new node")(c.nodeIds.contains(NodeId("hello_world")))
        _ = c.state.history.undo()
        _ <- waitFor(s"the undo, source:\n${c.source}")(c.source == dotSource && !c.nodeIds.contains(NodeId("hello_world")))
        _ = check(!c.state.history.canUndoNow, "the paste was more than one step")
      yield ()

  test("a paste on a locked diagram changes nothing, and the person gets a message"):
    withCanvas("paste-locked", dotSource): c =>
      c.state.metadata.locked.set(true)
      c.paste(textTransfer("Hello world"))
      c.paste(imageTransfer(Png2x1Base64))
      after(300):
        check(c.source == dotSource, s"the source:\n${c.source}")
        check(c.errors.size == 2 && c.errors.forall(_.contains("locked")), s"errors: ${c.errors}")

  test("a paste on a view-only diagram changes nothing, and the person gets a message"):
    val sequence = "sequenceDiagram\n  A->>B: hi"
    withCanvas("paste-view-only", sequence): c =>
      c.paste(textTransfer("Hello world"))
      after(300):
        check(c.source == sequence, s"the source:\n${c.source}")
        check(c.errors.size == 1 && c.errors.head.contains("view-only"), s"errors: ${c.errors}")

end CanvasPasteSpec

object CanvasPasteSpec:

  /** A PNG image of 2 × 1 pixels. */
  val Png2x1Base64 =
    "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAADklEQVR4nGP4z8DwH4QBEfcD/ePF9e8AAAAASUVORK5CYII="

  val Png2x1 = s"data:image/png;base64,$Png2x1Base64"

  def textTransfer(text: String): js.Dynamic =
    val transfer = js.Dynamic.newInstance(js.Dynamic.global.DataTransfer)()
    transfer.setData("text/plain", text)
    transfer

  /** A `DataTransfer` with one PNG file, as a screenshot on the clipboard gives it. */
  def imageTransfer(base64: String): js.Dynamic =
    val binary   = dom.window.atob(base64)
    val bytes    = new js.typedarray.Uint8Array(binary.length)
    binary.indices.foreach(i => bytes(i) = binary.charAt(i).toShort)
    val file     = js.Dynamic.newInstance(js.Dynamic.global.File)(js.Array(bytes), "shot.png", js.Dynamic.literal(`type` = "image/png"))
    val transfer = js.Dynamic.newInstance(js.Dynamic.global.DataTransfer)()
    transfer.items.add(file)
    transfer

  def hrefOf(el: dom.Element): String =
    Option(el.getAttribute("href"))
      .orElse(Option(el.getAttributeNS("http://www.w3.org/1999/xlink", "href")))
      .orElse(Option(el.getAttribute("xlink:href")))
      .getOrElse("")
