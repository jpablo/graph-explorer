package org.jpablo.graphexplorer.viewer.desktop

import com.raquo.airstream.eventbus.EventBus
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.render
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.command.SourceCommand
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.backends.LocalImages
import org.jpablo.graphexplorer.viewer.components.svgCanvas.{CanvasContainer, CanvasDropSpec, CanvasPasteSpec}
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, URL}
import org.jpablo.graphexplorer.viewer.graph.ImageNode
import org.jpablo.graphexplorer.viewer.models.{ElementId, NodeId}
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, ViewTarget, ViewerState}
import org.jpablo.graphexplorer.viewer.utils.{Platform, TestHelpers, TimerHelpers}
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** A diagram with a file on the desktop keeps its images and its file links next to the file.
  *
  *   - A pasted or a dropped image goes into `<dir>/<name>.assets/` (the `write_asset` command), and the node refers to
  *     it by a path relative to the diagram. The source holds no `data:` URI.
  *   - The desktop gives a drop as Tauri events with the paths of the files (`tauri://drag-drop`), and not as a DOM
  *     drop. An image file becomes an image node, and each other file becomes a node with a link to the file.
  *   - The page draws an image of a relative path from the asset URL that the shell allows (`local_image`).
  *   - A command-click on a file node asks the shell to open the file (`open_file_link`).
  *
  * The shell is a fake on `window.__TAURI__`. Its `convertFileSrc` gives a `blob:` URL of a small PNG for each path,
  * so that Graphviz and Mermaid can load the image in the test browser.
  */
class DesktopFilesSpec extends FunSuite with TestHelpers with TimerHelpers:
  import CanvasPasteSpec.{Png2x1Base64, hrefOf}
  import DesktopFilesSpec.*

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  private def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[DesktopFilesSpec] $clue")

  override def munitFixtures = List(mockStorageFixture())

  override def beforeEach(context: BeforeEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()

  override def afterEach(context: AfterEach): Unit =
    DesktopDocumentRegistry.reset()
    DesktopBridge.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private final class Canvas(val state: ViewerState, val box: dom.html.Element, val shell: Shell):
    val errors = mutable.Buffer.empty[String]
    val infos  = mutable.Buffer.empty[String]

    def container: dom.html.Element = box.querySelector("#canvas-container").asInstanceOf[dom.html.Element]
    def svg: Option[dom.Element]    = Option(container.querySelector(":scope > svg"))
    def source: String              = state.sourceText.now()
    def nodeIds: Set[NodeId]        = state.fullGraphNow().nodeIds.toSet
    def isDropTarget: Boolean       = container.classList.contains(CanvasDropSpec.DropTargetClass)

    def attr(id: String, name: org.jpablo.graphexplorer.viewer.models.AttributeId): Option[String] =
      state.fullGraphNow().nodes.get(NodeId(id)).flatMap(_.attributes.get(name)).map(_.toString)

    def imageSpec(id: String): Option[ImageNode.Spec] =
      state.fullGraphNow().nodes.get(NodeId(id)).flatMap(n => ImageNode.of(n.attributes))

    def images: Seq[String] = svg.toSeq.flatMap(_.querySelectorAll("image").toSeq).map(hrefOf)

    /** The position of the center of the canvas, as Tauri gives it (see `DesktopDrop.positionScale`). */
    def center: (Double, Double) =
      val r = container.getBoundingClientRect()
      val k = DesktopDrop.positionScale
      ((r.left + r.width / 2) * k, (r.top + r.height / 2) * k)

    /** A position out of the canvas: below its bottom edge. */
    def outside: (Double, Double) =
      val r = container.getBoundingClientRect()
      val k = DesktopDrop.positionScale
      ((r.left + r.width / 2) * k, (r.bottom + 50) * k)

    def paste(transfer: js.Dynamic): Unit =
      val init = js.Dynamic.literal(clipboardData = transfer, bubbles = true, cancelable = true)
      val ev   = js.Dynamic.newInstance(js.Dynamic.global.ClipboardEvent)("paste", init).asInstanceOf[dom.Event]
      container.dispatchEvent(ev)

  /** Show the file `path` with `text` in a canvas, as the desktop shows it. */
  private def withFile(path: String, text: String, drawn: Boolean = true)(body: Canvas => Future[Unit]): Future[Unit] =
    val shell = Shell()
    shell.install()
    val owner    = ManualOwner()
    val errorBus = EventBus[String]()
    val infoBus  = EventBus[String]()
    withGraphvizAsync: graphviz =>
      val open  = DesktopDocumentRegistry.record(path, "rev-1", text)
      val state = ViewerState(ViewTarget.File(open.id), graphviz, errorBus = errorBus, infoBus = infoBus)(using owner)
      val commands = Commands(state, RouterCommands(Router()))
      val box      = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      box.style.width = "800px"
      box.style.height = "600px"
      dom.document.body.appendChild(box)
      val root   = render(box, CanvasContainer(state, commands))
      val canvas = Canvas(state, box, shell)
      errorBus.events.foreach(canvas.errors += _)(using owner)
      infoBus.events.foreach(canvas.infos += _)(using owner)
      waitFor(s"the first drawing of $path")(
        (!drawn || canvas.svg.isDefined) && state.loadStatus.now() == DiagramLoadStatus.Ready
      )
        .flatMap(_ => body(canvas))
        .transform: outcome =>
          root.unmount()
          box.parentNode.removeChild(box)
          owner.killSubscriptions()
          outcome

  private val dotFile     = "/work/arch.dot"
  private val mermaidFile = "/work/flow.mmd"

  // ------------------------------------------------------------------ a pasted image

  test("DOT: a pasted image goes into the assets folder, and the source has its relative path"):
    withFile(dotFile, "digraph G {\n  a -> b\n}"): c =>
      c.paste(CanvasPasteSpec.imageTransfer(Png2x1Base64))
      for
        _ <- waitFor(s"the image node, errors ${c.errors}, source:\n${c.source}")(c.nodeIds.contains(NodeId("image1")))
        writes = c.shell.callsOf("write_asset")
        _ = check(writes.size == 1, s"the page did not call write_asset once: ${c.shell.commands}")
        _ = check(writes.head.diagramPath.asInstanceOf[String] == dotFile, s"diagramPath: ${writes.head.diagramPath}")
        _ = check(writes.head.ext.asInstanceOf[String] == "png", s"ext: ${writes.head.ext}")
        _ = check(writes.head.bytesBase64.asInstanceOf[String] == Png2x1Base64, "the bytes are not the bytes of the image")
        relative = c.shell.assetOf(Png2x1Base64, "png")
        _ = check(
          c.imageSpec("image1").contains(ImageNode.Spec(relative, Some((2, 1)), "")),
          s"the node: ${c.imageSpec("image1")}"
        )
        _ = check(c.source.contains(s"""image="$relative""""), s"the source:\n${c.source}")
        _ = check(!c.source.contains("data:"), s"the source has a data URI:\n${c.source}")
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  test("Mermaid: a pasted image goes into the assets folder, and the source has its relative path"):
    withFile(mermaidFile, "flowchart LR\n  a --> b"): c =>
      c.paste(CanvasPasteSpec.imageTransfer(Png2x1Base64))
      val relative = c.shell.assetOf(Png2x1Base64, "png")
      for
        _ <- waitFor(s"the image shape, errors ${c.errors}, source:\n${c.source}")(
               c.source.contains(s"""img: "$relative"""")
             )
        _ = check(c.shell.callsOf("write_asset").size == 1, s"calls: ${c.shell.commands}")
        _ = check(!c.source.contains("data:"), s"the source has a data URI:\n${c.source}")
        _ <- waitFor(s"the parse, errors ${c.errors}")(
               c.state.loadStatus.now() == DiagramLoadStatus.Ready && c.nodeIds.contains(NodeId("image1"))
             )
        // The drawing shows the image from the asset URL of its path.
        _ <- waitFor(s"the image in the drawing: ${c.images}")(c.images.contains(c.shell.urlOf(s"/work/$relative")))
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  // ------------------------------------------------------------------ a drop from the desktop

  test("a Tauri drop of an image and a PDF gives an image node and a file node with a link"):
    withFile(dotFile, "digraph G {\n  a -> b\n}"): c =>
      val paths = js.Array("/work/shots/shot.png", "/work/docs/report 2026.pdf", "/elsewhere/notes.txt")
      c.shell.emit("tauri://drag-enter", js.Dynamic.literal(paths = paths, position = position(c.center)))
      c.shell.emit("tauri://drag-drop", js.Dynamic.literal(paths = paths, position = position(c.center)))
      val relative = c.shell.assetOf(Png2x1Base64, "png")
      for
        _ <- waitFor(s"the nodes, errors ${c.errors}, source:\n${c.source}")(
               c.nodeIds.contains(NodeId("image1")) && c.nodeIds.contains(NodeId("report_2026")) &&
                 c.nodeIds.contains(NodeId("notes"))
             )
        _ = check(c.imageSpec("image1").map(_.src).contains(relative), s"the image: ${c.imageSpec("image1")}")
        _ = check(c.attr("report_2026", Label.attrId).contains("report 2026.pdf"), s"the label:\n${c.source}")
        _ = check(c.attr("report_2026", URL.attrId).contains("docs/report 2026.pdf"), s"the link:\n${c.source}")
        _ = check(c.attr("notes", URL.attrId).contains("file:///elsewhere/notes.txt"), s"the link:\n${c.source}")
        _ = check(c.shell.callsOf("write_asset").size == 1, s"calls: ${c.shell.commands}")
        _ = check(!c.isDropTarget, "the drop target stays after the drop")
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  test("Mermaid: a Tauri drop of a PDF gives a file node with a click line"):
    withFile(mermaidFile, "flowchart LR\n  a --> b"): c =>
      val paths = js.Array("/work/report.pdf")
      c.shell.emit("tauri://drag-drop", js.Dynamic.literal(paths = paths, position = position(c.center)))
      for
        _ <- waitFor(s"the click line, errors ${c.errors}, source:\n${c.source}")(
               c.source.contains("""click report href "report.pdf"""")
             )
        _ <- waitFor(s"the parse, errors ${c.errors}")(
               c.state.loadStatus.now() == DiagramLoadStatus.Ready && c.attr("report", URL.attrId).contains("report.pdf")
             )
        _ = check(c.errors.isEmpty, s"errors: ${c.errors}")
      yield ()

  test("in a free layout, a Tauri drop of two files puts the first at the drop point and the second below it"):
    import org.jpablo.graphexplorer.gxcore.model.LayoutPoint
    import org.jpablo.graphexplorer.viewer.components.svgCanvas.NewNodePlacementSpec.{centerOf, pxPerPoint, shapeOf, sizeLikeApp}
    withFile(dotFile, "digraph G {\n  a -> b\n}"): c =>
      sizeLikeApp(c.box)
      c.state.animateLayoutChanges.set(false)
      def nodeGroup(id: String) = c.svg.toSeq.flatMap(_.querySelectorAll("g.node").toSeq).find(_.id == NodeId(id).toSvg)
      val before = c.svg.get
      c.state.history.markGesture()
      c.state.placeNodes(Map(NodeId("a") -> LayoutPoint(0, 0), NodeId("b") -> LayoutPoint(0, -90)))
      for
        _ <- waitFor("the free layout drawing")(!(c.svg.get eq before) && nodeGroup("a").isDefined)
        _ <- after(30)(())
        g        = nodeGroup("a").get
        scale    = pxPerPoint(g)
        (ax, ay) = centerOf(shapeOf(g))
        // 45 points (5 grid steps) to the right of a. Tauri gives the point in its own unit (`DesktopDrop.positionScale`).
        p     = (ax + 45 * scale, ay)
        k     = DesktopDrop.positionScale
        paths = js.Array("/work/report.pdf", "/work/notes.txt")
        _     = c.shell.emit("tauri://drag-drop", js.Dynamic.literal(paths = paths, position = position((p._1 * k, p._2 * k))))
        _ <- waitFor(s"the nodes, errors ${c.errors}, source:\n${c.source}")(
               c.nodeIds.contains(NodeId("report")) && c.nodeIds.contains(NodeId("notes"))
             )
        report = c.state.layout.now().point("report")
        notes  = c.state.layout.now().point("notes")
        _ = check(report.contains(LayoutPoint(45, 0)), s"the place of the first file: $report")
        _ = check(notes.exists(n => n.x == 45 && n.y < -36), s"the place of the second file: $notes")
        _ <- waitFor("the drawing of the new nodes")(nodeGroup("report").isDefined && nodeGroup("notes").isDefined)
        _ <- after(30)(())
        (rx, ry) = centerOf(shapeOf(nodeGroup("report").get))
        _ = check(math.abs(rx - p._1) <= 9 * scale && math.abs(ry - p._2) <= 9 * scale, s"the first file is drawn at ($rx, $ry), the point is $p")
        _ = c.state.history.undo()
        _ = check(c.state.layout.now().point("report").isEmpty, s"one undo did not remove the places: ${c.state.layout.now()}")
        _ <- waitFor("the undo of the nodes")(!c.nodeIds.contains(NodeId("report")) && !c.nodeIds.contains(NodeId("notes")))
      yield ()

  test("the drop target follows the Tauri drag, and a drop out of the canvas adds nothing"):
    val text = "digraph G {\n  a -> b\n}"
    withFile(dotFile, text): c =>
      val paths = js.Array("/work/report.pdf")
      c.shell.emit("tauri://drag-enter", js.Dynamic.literal(paths = paths, position = position(c.center)))
      check(c.isDropTarget, "no drop target after a drag-enter over the canvas")
      c.shell.emit("tauri://drag-over", js.Dynamic.literal(position = position(c.outside)))
      check(!c.isDropTarget, "the drop target stays while the drag is out of the canvas")
      c.shell.emit("tauri://drag-over", js.Dynamic.literal(position = position(c.center)))
      check(c.isDropTarget, "no drop target after the drag came back over the canvas")
      c.shell.emit("tauri://drag-leave", js.Dynamic.literal())
      check(!c.isDropTarget, "the drop target stays after the drag left the window")
      c.shell.emit("tauri://drag-drop", js.Dynamic.literal(paths = paths, position = position(c.outside)))
      after(300):
        check(c.source == text, s"a drop out of the canvas changed the source:\n${c.source}")
        check(!c.isDropTarget, "the drop target shows after the drop")

  // ------------------------------------------------------------------ the drawing of a relative image

  test("DOT: the drawing shows an image of a relative path from its asset URL"):
    val text = "digraph G {\n  pic [shape=box, penwidth=0, label=\"\", fixedsize=true, width=0.0278, height=0.0139, image=\"arch.assets/a.png\", imagescale=true]\n}"
    withFile(dotFile, text): c =>
      for
        _ <- waitFor(s"the image in the drawing: ${c.images}, calls ${c.shell.commands}")(
               c.images.contains(c.shell.urlOf("/work/arch.assets/a.png"))
             )
        calls = c.shell.callsOf("local_image")
        _ = check(calls.exists(_.src.asInstanceOf[String] == "arch.assets/a.png"), s"local_image: ${c.shell.commands}")
        _ = check(calls.forall(_.diagramPath.asInstanceOf[String] == dotFile), "the diagram path of local_image")
        // The source keeps the path. Only the drawing has the asset URL.
        _ = check(c.source == text, s"the source:\n${c.source}")
      yield ()

  test("Mermaid: the drawing shows an image of a relative path from its asset URL"):
    val text = "flowchart LR\n  pic@{ img: \"arch.assets/a.png\", w: 40, h: 20, constraint: \"on\" }\n  pic --> b"
    withFile(mermaidFile, text): c =>
      for
        _ <- waitFor(s"the image in the drawing: ${c.images}, errors ${c.errors}")(
               c.images.contains(c.shell.urlOf("/work/arch.assets/a.png"))
             )
        _ = check(c.source == text, s"the source:\n${c.source}")
      yield ()

  test("Mermaid: an image that the shell does not find does not stop the drawing"):
    val text = "flowchart LR\n  pic@{ img: \"arch.assets/missing.png\", w: 40, h: 20, constraint: \"on\" }\n  pic --> b"
    withFile(mermaidFile, text): c =>
      for
        _ <- waitFor(s"the calls of local_image: ${c.shell.commands}")(c.shell.callsOf("local_image").nonEmpty)
        _ <- after(500)(())
        _ = check(c.svg.isDefined, "no drawing")
        _ = check(c.state.loadStatus.now() == DiagramLoadStatus.Ready, s"status: ${c.state.loadStatus.now()}")
        // The image shows as a gray box.
        _ = check(c.images.contains(LocalImages.Placeholder), s"the images of the drawing: ${c.images}")
      yield ()

  // ------------------------------------------------------------------ a canvas edit

  test("Mermaid: a canvas edit keeps the relative image path and the click line, with no refusal"):
    val text =
      "flowchart LR\n  pic@{ img: \"arch.assets/a.png\", w: 40, h: 20, constraint: \"on\" }\n  report[\"report.pdf\"]\n  pic --> b\n  click report href \"docs/report.pdf\""
    withFile(mermaidFile, text): c =>
      c.state.runSourceCommand(SourceCommand.SetAttribute(Set[ElementId](NodeId("b")), "color", "red"))
      for
        _ <- waitFor(s"the edit, errors ${c.errors}, source:\n${c.source}")(c.source != text)
        _ = check(c.errors.isEmpty, s"the edit was refused: ${c.errors}")
        _ = check(c.source.contains("""img: "arch.assets/a.png""""), s"the image path:\n${c.source}")
        _ = check(c.source.contains("""click report href "docs/report.pdf""""), s"the click line:\n${c.source}")
        _ = check(!c.source.contains("blob:"), s"the source has the asset URL:\n${c.source}")
      yield ()

  // ------------------------------------------------------------------ a command-click

  test("a command-click on a file node asks the shell to open the file"):
    val text = "digraph G {\n  report [shape=box, label=\"report.pdf\", URL=\"docs/report.pdf\"]\n}"
    withFile(dotFile, text): c =>
      val shape = c.svg.flatMap(svg => Option(svg.querySelector("g.node a polygon, g.node a text")))
      check(shape.isDefined, s"precondition: the node has no svg link: ${c.svg.map(_.outerHTML.take(400))}")
      val init = new dom.MouseEventInit:
        bubbles = true
        cancelable = true
        button = 0
        metaKey = Platform.isMac
        ctrlKey = !Platform.isMac
      val guard: js.Function1[dom.Event, Unit] = _.preventDefault()
      dom.window.addEventListener("click", guard)
      try shape.get.dispatchEvent(new dom.MouseEvent("click", init))
      finally dom.window.removeEventListener("click", guard)
      for
        _ <- waitFor(s"the open, calls ${c.shell.commands}")(c.shell.callsOf("open_file_link").nonEmpty)
        call = c.shell.callsOf("open_file_link").head
        _ = check(call.link.asInstanceOf[String] == "docs/report.pdf", s"link: ${call.link}")
        _ = check(call.diagramPath.asInstanceOf[String] == dotFile, s"diagramPath: ${call.diagramPath}")
        _ = check(c.shell.callsOf("open_external").isEmpty, "the page asked the browser to open a file")
      yield ()

  test("a command-click on a link to a program tells the person that the file manager shows it"):
    val text = "digraph G {\n  run [shape=box, label=\"run.command\", URL=\"run.command\"]\n}"
    withFile(dotFile, text): c =>
      c.shell.openAnswer = "revealed"
      val shape = c.svg.flatMap(svg => Option(svg.querySelector("g.node a polygon, g.node a text")))
      check(shape.isDefined, "precondition: the node has no svg link")
      val init = new dom.MouseEventInit:
        bubbles = true
        cancelable = true
        button = 0
        metaKey = Platform.isMac
        ctrlKey = !Platform.isMac
      val guard: js.Function1[dom.Event, Unit] = _.preventDefault()
      dom.window.addEventListener("click", guard)
      try shape.get.dispatchEvent(new dom.MouseEvent("click", init))
      finally dom.window.removeEventListener("click", guard)
      waitFor(s"the message, infos ${c.infos}")(c.infos.exists(_.contains("run.command")))

end DesktopFilesSpec

object DesktopFilesSpec:

  def position(xy: (Double, Double)): js.Dynamic = js.Dynamic.literal(x = xy._1, y = xy._2)

  /** A fake desktop shell on `window.__TAURI__`: the commands, `convertFileSrc`, and the events. */
  final class Shell:
    val calls: mutable.Buffer[(String, js.Dynamic)] = mutable.Buffer.empty
    private val listeners = mutable.Map.empty[String, List[js.Function1[js.Any, Unit]]]
    private val urls      = mutable.Map.empty[String, String]

    /** The answer of `open_file_link`. */
    var openAnswer = "opened"

    def commands: List[String] = calls.toList.map(_._1)

    def callsOf(command: String): List[js.Dynamic] = calls.toList.collect { case (`command`, a) => a }

    /** The relative path that `write_asset` gives for these bytes. */
    def assetOf(base64: String, ext: String): String = f"arch.assets/img-${base64.hashCode & 0xffffffffL}%08x.$ext"

    /** The asset URL of `path`: a `blob:` URL of a PNG of 2 × 1 pixels, the same for each call. */
    def urlOf(path: String): String =
      urls.getOrElseUpdate(
        path, {
          val bytes = CanvasDropSpec.bytesOf(CanvasPasteSpec.Png2x1Base64)
          val blob  = js.Dynamic.newInstance(js.Dynamic.global.Blob)(js.Array(bytes), js.Dynamic.literal(`type` = "image/png"))
          js.Dynamic.global.URL.createObjectURL(blob).asInstanceOf[String]
        }
      )

    def emit(event: String, payload: js.Any): Unit =
      listeners.getOrElse(event, Nil).foreach(_(js.Dynamic.literal(event = event, id = 1, payload = payload)))

    private def ok(value: js.Any): js.Promise[js.Any] = js.Promise.resolve[js.Any](value)

    private def str(a: js.Dynamic, name: String): String = a.selectDynamic(name).asInstanceOf[String]

    def install(): Unit =
      val invoke: js.Function2[String, js.Any, js.Promise[js.Any]] = (command, rawArgs) =>
        val a = rawArgs.asInstanceOf[js.Dynamic]
        calls += command -> a
        command match
          case "hash_text" => ok(f"${str(a, "text").hashCode & 0xffffffffL}%08x")
          case "open_document" =>
            ok(js.Dynamic.literal(path = a.selectDynamic("path"), revision = "seed", text = ""))
          case "library_write" | "library_list" => ok(js.Array())
          case "set_unsaved" | "viewer_ready"   => ok(())
          case "write_asset"                    => ok(assetOf(str(a, "bytesBase64"), str(a, "ext")))
          case "local_image" =>
            val src = str(a, "src")
            if src.contains("missing") then js.Promise.reject(js.Dynamic.literal(code = "IMAGE_MISSING", message = src))
            else
              val dir = str(a, "diagramPath").reverse.dropWhile(_ != '/').reverse
              ok(js.Dynamic.literal(path = s"$dir$src", headBase64 = CanvasPasteSpec.Png2x1Base64))
          case "open_file_link" => ok(openAnswer)
          case other            => js.Promise.reject(js.Dynamic.literal(code = "UNKNOWN_COMMAND", message = other))
      val convertFileSrc: js.Function1[String, String] = path => urlOf(path)
      val listen: js.Function2[String, js.Function1[js.Any, Unit], js.Promise[js.Any]] = (event, handler) =>
        listeners(event) = listeners.getOrElse(event, Nil) :+ handler
        val unlisten: js.Function0[Unit] = () => listeners(event) = listeners.getOrElse(event, Nil).filterNot(_ eq handler)
        ok(unlisten)
      js.Dynamic.global.window.__TAURI__ = js.Dynamic.literal(
        core = js.Dynamic.literal(invoke = invoke, convertFileSrc = convertFileSrc),
        event = js.Dynamic.literal(listen = listen)
      )
