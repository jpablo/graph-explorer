package org.jpablo.graphexplorer.viewer.components.svgCanvas

import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, URL}
import org.jpablo.graphexplorer.viewer.graph.ImageNode
import org.jpablo.graphexplorer.viewer.models.{ElementIds, NodeId}
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, ViewTarget, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.{ImageHeader, TestHelpers, TimerHelpers}
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** A drop of files, text or a link on the canvas puts new nodes on the canvas.
  *
  *   - Each image file becomes an image node. An image wider than 800 pixels is stored at 800 pixels wide.
  *   - Each other file becomes a node with the name of the file. A browser file has no path, so the node has no link.
  *   - Text becomes a text node, and a link becomes a node with the link.
  *
  * Each test sends real `dragenter`, `dragover` and `drop` events, with a `DataTransfer`, to the canvas container.
  */
class CanvasDropSpec extends FunSuite with TestHelpers with TimerHelpers:
  import CanvasDropSpec.*
  import CanvasPasteSpec.{Png2x1, Png2x1Base64, hrefOf}

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[CanvasDropSpec] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private final class Canvas(val state: ViewerState, val box: dom.html.Element):
    val errors = mutable.Buffer.empty[String]
    val infos  = mutable.Buffer.empty[String]

    def container: dom.html.Element = box.querySelector("#canvas-container").asInstanceOf[dom.html.Element]
    def svg: Option[dom.Element]    = Option(container.querySelector(":scope > svg"))
    def source: String              = state.sourceText.now()
    def nodeIds: Set[NodeId]        = state.fullGraphNow().nodeIds.toSet
    def isDropTarget: Boolean       = container.classList.contains(DropTargetClass)

    def attr(id: String, name: org.jpablo.graphexplorer.viewer.models.AttributeId): Option[String] =
      state.fullGraphNow().nodes.get(NodeId(id)).flatMap(_.attributes.get(name)).map(_.toString)

    def imageSpec(id: String): Option[ImageNode.Spec] =
      state.fullGraphNow().nodes.get(NodeId(id)).flatMap(n => ImageNode.of(n.attributes))

    /** The link of the `image` element in the group of the node `id`, as Graphviz draws it. */
    def imageOfNode(id: String): Option[String] =
      svg.toSeq
        .flatMap(_.querySelectorAll("g.node").toSeq)
        .find(_.id == NodeId(id).toSvg)
        .flatMap(g => Option(g.querySelector("image")))
        .map(hrefOf)

    /** Send a drag event to `target`. The result is false when a handler called `preventDefault`. */
    def drag(kind: String, transfer: js.Dynamic, target: dom.EventTarget = container): Boolean =
      val init = js.Dynamic.literal(dataTransfer = transfer, bubbles = true, cancelable = true)
      val ev   = js.Dynamic.newInstance(js.Dynamic.global.DragEvent)(kind, init).asInstanceOf[dom.Event]
      target.dispatchEvent(ev)

    /** The whole drag of a person: the drag comes in, moves over the canvas, and drops. */
    def dropOn(transfer: js.Dynamic): Unit =
      drag("dragenter", transfer)
      drag("dragover", transfer)
      drag("drop", transfer)

    def paste(transfer: js.Dynamic): Unit =
      val init = js.Dynamic.literal(clipboardData = transfer, bubbles = true, cancelable = true)
      val ev   = js.Dynamic.newInstance(js.Dynamic.global.ClipboardEvent)("paste", init).asInstanceOf[dom.Event]
      container.dispatchEvent(ev)

  private def withCanvas(name: String, source: String, drawn: Boolean = true)(body: Canvas => Future[Unit]): Future[Unit] =
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
      waitFor(s"the first drawing of $name")(
        (!drawn || canvas.svg.isDefined) && state.loadStatus.now() != DiagramLoadStatus.Loading
      )
        .flatMap(_ => body(canvas))
        .transform: outcome =>
          root.unmount()
          box.parentNode.removeChild(box)
          owner.killSubscriptions()
          outcome

  private val dotSource     = "digraph G {\n  a -> b\n}"
  private val mermaidSource = "flowchart TD\n  a --> b"
  private val groupSource   = "digraph G {\n  a\n  subgraph cluster_x {\n    label=\"x\"\n    c\n  }\n}"

  // ------------------------------------------------------------------ the drop target

  test("a drag of a file over the canvas shows the drop target, and the browser does not open the file"):
    withCanvas("drop-target", dotSource): c =>
      val transfer = filesTransfer(pngFile("shot.png", Png2x1Base64))
      check(!c.drag("dragenter", transfer), "the dragenter is not cancelled")
      check(c.isDropTarget, "no drop target after the dragenter")
      check(!c.drag("dragover", transfer), "the dragover is not cancelled: the browser opens the file")
      // Chrome keeps the drop effect of a `DataTransfer` of a script at "none", so the test cannot read it.
      check(!c.drag("drop", transfer), "the drop is not cancelled: the browser opens the file")
      check(!c.isDropTarget, "the drop target stays after the drop")
      Future.unit

  test("the drop target stays while the drag moves over the drawing, and goes away when the drag leaves"):
    withCanvas("drop-target-leave", dotSource): c =>
      val transfer = filesTransfer(pngFile("shot.png", Png2x1Base64))
      val child    = c.svg.get
      // The drag goes from the container to the drawing: the browser first enters the child, then leaves the parent.
      c.drag("dragenter", transfer)
      c.drag("dragenter", transfer, child)
      c.drag("dragleave", transfer)
      check(c.isDropTarget, "the drop target went away over the drawing")
      c.drag("dragover", transfer, child)
      check(c.isDropTarget, "the drop target went away during the dragover")
      // The drag leaves the window from the drawing.
      c.drag("dragleave", transfer, child)
      check(!c.isDropTarget, "the drop target stays after the drag left")
      check(c.source == dotSource, s"the source:\n${c.source}")
      Future.unit

  // ------------------------------------------------------------------ files

  test("DOT: a drop of a PNG file adds an image node, and Graphviz draws the image"):
    withCanvas("drop-image-dot", dotSource): c =>
      c.dropOn(filesTransfer(pngFile("shot.png", Png2x1Base64)))
      for
        _ <- waitFor(s"the image node, source:\n${c.source}")(c.nodeIds.contains(NodeId("image1")))
        _ = check(c.imageSpec("image1").contains(ImageNode.Spec(Png2x1, Some((2, 1)), "")), s"the node: ${c.imageSpec("image1")}")
        _ <- waitFor("the image in the drawing")(c.imageOfNode("image1").contains(Png2x1))
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  test("DOT: a drop of an unknown file adds a node with the file name and no link"):
    withCanvas("drop-pdf", dotSource): c =>
      c.dropOn(filesTransfer(otherFile("report 2026.pdf", "application/pdf")))
      for
        _ <- waitFor(s"the file node, source:\n${c.source}")(c.nodeIds.contains(NodeId("report_2026")))
        _ = check(c.attr("report_2026", Label.attrId).contains("report 2026.pdf"), s"the label, source:\n${c.source}")
        _ = check(c.attr("report_2026", URL.attrId).isEmpty, s"the node has a link:\n${c.source}")
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  test("Mermaid: a drop of a .mmd file adds a node with the file name, and the diagram stays"):
    withCanvas("drop-mmd", mermaidSource): c =>
      c.dropOn(filesTransfer(otherFile("flow.mmd", "", "flowchart LR\n  x --> y")))
      for
        _ <- waitFor(s"the file node, source:\n${c.source}")(c.source.contains("flow.mmd"))
        _ <- waitFor(s"the parse, errors: ${c.errors}")(
               c.state.loadStatus.now() == DiagramLoadStatus.Ready && c.nodeIds.contains(NodeId("flow"))
             )
        // The printer of a canvas edit writes `TD` as `TB`, which is the same direction.
        _ = check(c.source.startsWith("flowchart"), s"the diagram changed:\n${c.source}")
        _ = check(!c.nodeIds.contains(NodeId("x")), s"the content of the file went into the diagram:\n${c.source}")
        _ = check(c.nodeIds == Set(NodeId("a"), NodeId("b"), NodeId("flow")), s"the nodes: ${c.nodeIds}\n${c.source}")
        _ = check(c.attr("flow", Label.attrId).contains("flow.mmd"), s"the label:\n${c.source}")
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  test("Mermaid: a drop of a PNG file adds an image shape, and Mermaid draws the image"):
    withCanvas("drop-image-mermaid", mermaidSource): c =>
      c.dropOn(filesTransfer(pngFile("shot.png", Png2x1Base64)))
      for
        _ <- waitFor(s"the parse of the image shape, errors: ${c.errors}")(
               c.state.loadStatus.now() == DiagramLoadStatus.Ready && c.nodeIds.contains(NodeId("image1"))
             )
        _ <- waitFor("the image in the drawing")(
               c.svg.toSeq.flatMap(_.querySelectorAll("image").toSeq).exists(hrefOf(_) == Png2x1)
             )
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  test("a drop of several files adds one node for each file, and one undo removes all of them"):
    withCanvas("drop-several", dotSource): c =>
      c.dropOn(
        filesTransfer(
          pngFile("shot.png", Png2x1Base64),
          otherFile("report.pdf", "application/pdf"),
          otherFile("other.dot", "", "digraph H { x -> y }"),
          pngFile("shot2.png", Png2x1Base64)
        )
      )
      val added = Set(NodeId("image1"), NodeId("report"), NodeId("other"), NodeId("image2"))
      for
        _ <- waitFor(s"the new nodes, source:\n${c.source}")(added.subsetOf(c.nodeIds))
        _ = check(c.nodeIds == added + NodeId("a") + NodeId("b"), s"the nodes: ${c.nodeIds}")
        _ = check(sameIds(c.state.selection.now(), added), s"the selection: ${c.state.selection.now()}")
        _ = c.state.history.undo()
        _ <- waitFor(s"the undo, source:\n${c.source}")(c.source == dotSource)
        _ = check(!c.state.history.canUndoNow, "the drop was more than one step")
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  // ------------------------------------------------------------------ text and links

  test("a drop of text adds a text node"):
    withCanvas("drop-text", dotSource): c =>
      c.dropOn(CanvasPasteSpec.textTransfer("Hello world"))
      for
        _ <- waitFor(s"the text node, source:\n${c.source}")(c.nodeIds.contains(NodeId("hello_world")))
        _ = check(c.attr("hello_world", Label.attrId).contains("Hello world"), s"the source:\n${c.source}")
      yield ()

  test("a drop of a link adds a node with the link as its label and its URL"):
    val link = "https://example.com/docs/a.pdf"
    withCanvas("drop-link", dotSource): c =>
      val transfer = js.Dynamic.newInstance(js.Dynamic.global.DataTransfer)()
      transfer.setData("text/uri-list", s"# a comment\n$link")
      transfer.setData("text/plain", link)
      c.dropOn(transfer)
      for
        _ <- waitFor(s"the link node, source:\n${c.source}")(c.source.contains(s"""URL="$link""""))
        id = (c.nodeIds -- Set(NodeId("a"), NodeId("b"))).head.value
        _ = check(c.attr(id, Label.attrId).contains(link), s"the label, source:\n${c.source}")
        _ = check(c.attr(id, URL.attrId).contains(link), s"the URL, source:\n${c.source}")
      yield ()

  // ------------------------------------------------------------------ the selected group

  test("a dropped node goes into the selected group"):
    withCanvas("drop-group", groupSource): c =>
      val group = c.state.fullGraphNow().membership(NodeId("c")).get
      c.state.selection.set(ElementIds.from(group))
      c.dropOn(filesTransfer(otherFile("report.pdf", "application/pdf")))
      for
        _ <- waitFor(s"the file node, source:\n${c.source}")(c.nodeIds.contains(NodeId("report")))
        _ = check(c.state.fullGraphNow().membership(NodeId("report")).contains(group), s"not in the group:\n${c.source}")
        _ = check(sameIds(c.state.selection.now(), Set(NodeId("report"))), s"the selection: ${c.state.selection.now()}")
      yield ()

  test("a pasted node goes into the selected group"):
    withCanvas("paste-group", groupSource): c =>
      val group = c.state.fullGraphNow().membership(NodeId("c")).get
      c.state.selection.set(ElementIds.from(group))
      c.paste(CanvasPasteSpec.textTransfer("Hello world"))
      for
        _ <- waitFor(s"the text node, source:\n${c.source}")(c.nodeIds.contains(NodeId("hello_world")))
        _ = check(c.state.fullGraphNow().membership(NodeId("hello_world")).contains(group), s"not in the group:\n${c.source}")
      yield ()

  test("a dropped node goes to the top level when a node is selected"):
    withCanvas("drop-node-selected", groupSource): c =>
      c.state.selection.set(ElementIds.from(NodeId("a")))
      c.dropOn(CanvasPasteSpec.textTransfer("Hello world"))
      for
        _ <- waitFor(s"the text node, source:\n${c.source}")(c.nodeIds.contains(NodeId("hello_world")))
        _ = check(c.state.fullGraphNow().membership(NodeId("hello_world")).forall(_.value == "root"), s"in a group:\n${c.source}")
        _ = check(c.state.fullGraphNow().arrows.isEmpty, s"the drop added an arrow:\n${c.source}")
      yield ()

  // ------------------------------------------------------------------ big images

  test("a dropped PNG wider than 800 pixels is stored at 800 pixels wide, and shows at 400 pixels"):
    withCanvas("drop-big-png", dotSource): c =>
      c.dropOn(filesTransfer(drawnFile("big.png", "image/png", 1600, 800)))
      for
        _ <- waitFor(s"the image node, errors: ${c.errors}")(c.nodeIds.contains(NodeId("image1")))
        spec = c.imageSpec("image1").get
        _ = check(spec.size.contains((400, 200)), s"the display size: ${spec.size}")
        _ = check(spec.src.startsWith("data:image/png;base64,"), s"the type: ${spec.src.take(40)}")
        _ = check(ImageHeader.ofDataUri(spec.src).contains(ImageHeader.Size(800, 400)), s"the stored size: ${ImageHeader.ofDataUri(spec.src)}")
        _ <- waitFor("the image in the drawing")(c.imageOfNode("image1").isDefined)
      yield ()

  test("a dropped JPEG wider than 800 pixels stays a JPEG, at 800 pixels wide"):
    withCanvas("drop-big-jpeg", dotSource): c =>
      c.dropOn(filesTransfer(drawnFile("photo.jpg", "image/jpeg", 1600, 800)))
      for
        _ <- waitFor(s"the image node, errors: ${c.errors}")(c.nodeIds.contains(NodeId("image1")))
        spec = c.imageSpec("image1").get
        _ = check(spec.src.startsWith("data:image/jpeg;base64,"), s"the type: ${spec.src.take(40)}")
        _ = check(ImageHeader.ofDataUri(spec.src).contains(ImageHeader.Size(800, 400)), s"the stored size: ${ImageHeader.ofDataUri(spec.src)}")
        _ = check(spec.size.contains((400, 200)), s"the display size: ${spec.size}")
      yield ()

  test("a pasted PNG wider than 800 pixels is also stored at 800 pixels wide"):
    withCanvas("paste-big-png", dotSource): c =>
      val transfer = js.Dynamic.newInstance(js.Dynamic.global.DataTransfer)()
      transfer.items.add(drawnFile("big.png", "image/png", 1600, 800))
      c.paste(transfer)
      for
        _ <- waitFor(s"the image node, errors: ${c.errors}")(c.nodeIds.contains(NodeId("image1")))
        spec = c.imageSpec("image1").get
        _ = check(ImageHeader.ofDataUri(spec.src).contains(ImageHeader.Size(800, 400)), s"the stored size: ${ImageHeader.ofDataUri(spec.src)}")
        _ = check(spec.size.contains((400, 200)), s"the display size: ${spec.size}")
      yield ()

  test("a dropped image of 300 pixels is stored as it is"):
    val file = drawnFile("small.png", "image/png", 300, 150)
    withCanvas("drop-small-png", dotSource): c =>
      c.dropOn(filesTransfer(file))
      for
        _ <- waitFor(s"the image node, errors: ${c.errors}")(c.nodeIds.contains(NodeId("image1")))
        spec = c.imageSpec("image1").get
        _ = check(spec.src == drawnUri("image/png", 300, 150), "the image changed")
        _ = check(spec.size.contains((300, 150)), s"the display size: ${spec.size}")
      yield ()

  // ------------------------------------------------------------------ refusals

  test("a drop on a locked diagram changes nothing, and the person gets a message"):
    withCanvas("drop-locked", dotSource): c =>
      c.state.metadata.locked.set(true)
      c.dropOn(filesTransfer(pngFile("shot.png", Png2x1Base64), otherFile("report.pdf", "application/pdf")))
      c.dropOn(CanvasPasteSpec.textTransfer("Hello world"))
      after(300):
        check(c.source == dotSource, s"the source:\n${c.source}")
        check(c.errors.size == 2 && c.errors.forall(_.contains("locked")), s"errors: ${c.errors}")

  test("a drop on a view-only diagram changes nothing, and the person gets a message"):
    val sequence = "sequenceDiagram\n  A->>B: hi"
    withCanvas("drop-view-only", sequence): c =>
      c.dropOn(filesTransfer(otherFile("report.pdf", "application/pdf")))
      after(300):
        check(c.source == sequence, s"the source:\n${c.source}")
        check(c.errors.size == 1 && c.errors.head.contains("view-only"), s"errors: ${c.errors}")

  test("a drop on a diagram with an error in its text changes nothing, and the person gets a message"):
    val broken = "digraph G {\n  a -> \n"
    withCanvas("drop-failed", broken, drawn = false): c =>
      c.dropOn(filesTransfer(otherFile("report.pdf", "application/pdf")))
      after(300):
        check(c.source == broken, s"the source:\n${c.source}")
        check(c.errors.exists(_.contains("error")), s"errors: ${c.errors}")

  test("a drop of an image on a Mermaid state diagram changes nothing, and the person gets a message"):
    val stateDiagram = "stateDiagram-v2\n  [*] --> A\n  A --> B"
    withCanvas("drop-state", stateDiagram): c =>
      c.dropOn(filesTransfer(pngFile("shot.png", Png2x1Base64)))
      after(300):
        check(c.source == stateDiagram, s"the source:\n${c.source}")
        check(c.errors.size == 1 && c.errors.head.contains("state diagram"), s"errors: ${c.errors}")

end CanvasDropSpec

object CanvasDropSpec:

  val DropTargetClass = "gx-drop-target"

  def file(name: String, mime: String, bytes: js.typedarray.Uint8Array): dom.File =
    js.Dynamic.newInstance(js.Dynamic.global.File)(js.Array(bytes), name, js.Dynamic.literal(`type` = mime)).asInstanceOf[dom.File]

  def bytesOf(base64: String): js.typedarray.Uint8Array =
    val binary = dom.window.atob(base64)
    val bytes  = new js.typedarray.Uint8Array(binary.length)
    binary.indices.foreach(i => bytes(i) = binary.charAt(i).toShort)
    bytes

  def sameIds(selection: ElementIds, ids: Set[NodeId]): Boolean =
    selection.size == ids.size && ids.forall(selection.contains)

  def pngFile(name: String, base64: String): dom.File = file(name, "image/png", bytesOf(base64))

  def otherFile(name: String, mime: String, text: String = "%PDF-1.4"): dom.File =
    val bytes = new js.typedarray.Uint8Array(text.length)
    text.indices.foreach(i => bytes(i) = text.charAt(i).toShort)
    file(name, mime, bytes)

  /** The `data:` URI of a drawn image of `width` × `height` pixels: two colors, so that the image is not empty. The
    * drawing is the same each time, so two calls give the same URI.
    */
  def drawnUri(mime: String, width: Int, height: Int): String =
    val canvas = dom.document.createElement("canvas").asInstanceOf[dom.html.Canvas]
    canvas.width = width
    canvas.height = height
    val ctx = canvas.getContext("2d").asInstanceOf[dom.CanvasRenderingContext2D]
    ctx.fillStyle = "#3366cc"
    ctx.fillRect(0, 0, width.toDouble, height.toDouble)
    ctx.fillStyle = "#ffcc00"
    ctx.fillRect(width / 4.0, height / 4.0, width / 2.0, height / 2.0)
    canvas.toDataURL(mime)

  /** A file of a drawn image of `width` × `height` pixels. */
  def drawnFile(name: String, mime: String, width: Int, height: Int): dom.File =
    file(name, mime, bytesOf(drawnUri(mime, width, height).dropWhile(_ != ',').drop(1)))

  /** A `DataTransfer` with `files`, as the drop of files from the desktop gives it. */
  def filesTransfer(files: dom.File*): js.Dynamic =
    val transfer = js.Dynamic.newInstance(js.Dynamic.global.DataTransfer)()
    files.foreach(f => transfer.items.add(f))
    transfer
