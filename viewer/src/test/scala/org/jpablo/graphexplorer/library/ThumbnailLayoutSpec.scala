package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramLayout, DiagramMetadata, LayoutPoint, LibraryEntry}
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.backends.DefaultDiagramLanguages
import org.jpablo.graphexplorer.viewer.desktop.DesktopDocumentRegistry
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.DiagramSnapshot
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The card of a diagram in the manual mode shows the stored layout (ADR 0004).
  *
  * Found on 2026-11-27 on the desktop: the card of a manual diagram showed the automatic layout of `dot`, and the view
  * of the diagram showed the stored positions. The card drew the source only, with no layout.
  *
  * The layout below puts `b` at the right of `a`. `dot` puts `b` under `a`. The bend of the arrow is above the two
  * nodes, so the arrow goes up and comes down.
  */
class ThumbnailLayoutSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "ThumbnailLayoutSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  override def afterEach(context: AfterEach): Unit =
    Library.restoreDefault()
    DesktopDocumentRegistry.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  private val manualLayout: DiagramLayout =
    DiagramLayout(manual = true)
      .placed(Map("a" -> LayoutPoint(0, 0), "b" -> LayoutPoint(200, 0)))
      .withBends("a->b#1", Vector(LayoutPoint(100, 150)))

  /** The center of the shape of node `id` in the card, in the units of the SVG (the y axis goes down). */
  private def centerOf(card: dom.Element, id: String): Option[(Double, Double)] =
    card
      .querySelectorAll("g.node")
      .toSeq
      .collect { case g: dom.Element => g }
      .find(g => Option(g.querySelector("title")).exists(_.textContent == id))
      .flatMap: g =>
        Option(g.querySelector("ellipse"))
          .map(e => (e.getAttribute("cx").toDouble, e.getAttribute("cy").toDouble))
          .orElse(Option(g.querySelector("polygon")).map(boxCenter))

  /** The center of the box of the points of a polygon: the shape of a box node (a Mermaid node drawn by Graphviz). */
  private def boxCenter(polygon: dom.Element): (Double, Double) =
    val points = polygon.getAttribute("points").trim.split("\\s+").map(_.split(",").map(_.toDouble))
    val xs     = points.map(_(0))
    val ys     = points.map(_(1))
    ((xs.min + xs.max) / 2, (ys.min + ys.max) / 2)

  /** The smallest y of the points of the arrow path in the card: the top of the arrow. */
  private def arrowTop(card: dom.Element): Option[Double] =
    Option(card.querySelector("g.edge path")).map: p =>
      "-?[0-9.]+,-?[0-9.]+".r.findAllIn(p.getAttribute("d")).map(_.split(",")(1).toDouble).min

  /** The faults of the card against the stored layout. */
  private def layoutFaults(card: dom.Element): Seq[String] =
    (centerOf(card, "a"), centerOf(card, "b")) match
      case (Some((ax, ay)), Some((bx, by))) =>
        val placeFaults = Seq(
          Option.when(math.abs((bx - ax) - 200) > 1)(s"b is ${bx - ax} to the right of a, and the layout says 200"),
          Option.when(math.abs(by - ay) > 1)(s"b is ${by - ay} under a, and the layout says 0")
        ).flatten
        val bendFault = arrowTop(card) match
          case Some(top) if top < ay - 100 => None
          case other                        => Some(s"the top of the arrow is $other, and the bend is 150 above a (y $ay)")
        placeFaults ++ bendFault.toSeq
      case other => Seq(s"the card does not show the nodes a and b: $other in ${card.innerHTML.take(300)}")

  /** Draw the card of each diagram, wait for the thumbnails, and give the card elements. */
  private def drawCards(ids: Seq[DiagramId])(body: Seq[dom.Element] => Unit): Future[Unit] =
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    withGraphvizAsync: graphviz =>
      val router    = Router()
      val languages = DefaultDiagramLanguages(graphviz)
      val summaries = ids.map(id => Library.indexNow().diagrams.find(_.id == id).get)
      val cards     = summaries.map(d => diagramCard(graphviz, router, Library.diagramCardInfo(d.id, languages), d))
      val root      = render(container, div(cards))
      def ready = container.querySelectorAll("g.node").length >= 2 * ids.size
      def wait(left: Int): Future[Unit] =
        if ready || left == 0 then Future.unit else after(100).flatMap(_ => wait(left - 1))
      wait(80).map: _ =>
        try body(cards.map(_.ref))
        finally root.unmount()
    .transform: result =>
      container.parentNode.removeChild(container)
      result

  test("the card of a browser diagram in the manual mode shows the stored positions and bends"):
    val id = Library.createDiagram("manual card", "digraph G {\n  a -> b // browser\n}")
    Library.storeDiagram(id, DiagramSnapshot.minimal(Some("digraph G {\n  a -> b // browser\n}")).copy(layout = manualLayout))
    drawCards(Seq(id)): cards =>
      val faults = layoutFaults(cards.head)
      check(faults.isEmpty, s"the card does not show the stored layout: ${faults.mkString("; ")}")

  test("two diagrams with the same source and different layouts have different cards"):
    val source = "digraph G {\n  a -> b // same source\n}"
    val auto   = Library.createDiagram("automatic card", source)
    val manual = Library.createDiagram("manual card", source)
    Library.storeDiagram(manual, DiagramSnapshot.minimal(Some(source)).copy(layout = manualLayout))
    // The automatic card first: a cache by source only then gives its drawing to the manual card.
    drawCards(Seq(auto)): cards =>
      check(centerOf(cards.head, "a").isDefined, "precondition: the automatic card shows no node a")
    .flatMap: _ =>
      drawCards(Seq(manual)): cards =>
        val faults = layoutFaults(cards.head)
        check(faults.isEmpty, s"the manual card does not show the stored layout: ${faults.mkString("; ")}")

  test("the card of a desktop file in the manual mode shows the layout of its side file"):
    val shell = FakeDesktopShell()
    shell.install()
    shell.files("/w/manual.dot") = "digraph G {\n  a -> b // desktop\n}"
    shell.layouts("/w/manual.dot") = DiagramLayout.render(manualLayout)
    shell.putEntry(LibraryEntry("/w/manual.dot", DiagramMetadata(), 2, 2))
    DesktopLibrary
      .load()
      .flatMap: library =>
        Library.install(library)
        drawCards(library.indexNow().diagrams.map(_.id)): cards =>
          val faults = layoutFaults(cards.head)
          check(faults.isEmpty, s"the card does not show the layout of the side file: ${faults.mkString("; ")}")
      .transform: result =>
        shell.uninstall()
        result

  test("the card of a Mermaid flowchart in the manual mode shows the stored positions and bends"):
    // In the manual mode, Graphviz draws the graph of a Mermaid flowchart, as the view does (ADR 0004).
    val source = "flowchart TD\n  a --> b"
    val id     = Library.createDiagram("manual flowchart", source)
    Library.storeDiagram(id, DiagramSnapshot.minimal(Some(source)).copy(layout = manualLayout))
    drawCards(Seq(id)): cards =>
      val faults = layoutFaults(cards.head)
      check(faults.isEmpty, s"the card of the flowchart does not show the stored layout: ${faults.mkString("; ")}")

  test("the card of a diagram in the manual mode draws the visible graph of the view: its theme, with no hidden node"):
    // The view draws `toVisibleGraph`: the hidden elements go away, and the theme of the view gives each node a box.
    // A source node with no shape is an ellipse in plain Graphviz, and a box in the view.
    val source = "digraph G {\n  a -> b\n  b -> c // visible graph\n}"
    val id     = Library.createDiagram("manual visible graph", source)
    Library.storeDiagram(
      id,
      DiagramSnapshot
        .minimal(Some(source))
        .copy(layout = manualLayout, metadata = DiagramMetadata(hiddenElements = Set("node:c")))
    )
    drawCards(Seq(id)): cards =>
      val card  = cards.head
      val nodes = card.querySelectorAll("g.node").toSeq.collect { case g: dom.Element => g }
      val names = nodes.flatMap(g => Option(g.querySelector("title")).map(_.textContent))
      check(!names.contains("c"), s"the card shows the hidden node c: ${names.mkString(", ")}")
      val ellipses = nodes.filter(_.querySelector("ellipse") != null).flatMap(g => Option(g.querySelector("title")).map(_.textContent))
      check(ellipses.isEmpty, s"the card draws these nodes as ellipses, and the view draws boxes: ${ellipses.mkString(", ")}")
      val faults = layoutFaults(card)
      check(faults.isEmpty, s"the card does not show the stored layout: ${faults.mkString("; ")}")

  test("the card of an automatic DOT diagram draws the visible graph of the view: its theme, with no hidden node"):
    val source = "digraph G {\n  a -> b\n  b -> c // automatic visible graph\n}"
    val id     = Library.createDiagram("automatic visible graph", source)
    Library.storeDiagram(id, DiagramSnapshot.minimal(Some(source)).copy(metadata = DiagramMetadata(hiddenElements = Set("node:c"))))
    drawCards(Seq(id)): cards =>
      val nodes = cards.head.querySelectorAll("g.node").toSeq.collect { case g: dom.Element => g }
      val names = nodes.flatMap(g => Option(g.querySelector("title")).map(_.textContent))
      check(names.sorted == Seq("a", "b"), s"the card shows the nodes ${names.mkString(", ")}, and the view shows a, b")
      val ellipses = nodes.filter(_.querySelector("ellipse") != null).flatMap(g => Option(g.querySelector("title")).map(_.textContent))
      check(ellipses.isEmpty, s"the card draws these nodes as ellipses, and the view draws boxes: ${ellipses.mkString(", ")}")

  test("the card of an automatic Mermaid flowchart shows no hidden node"):
    val source = "flowchart TD\n  A[Alpha] --> B[Beta]\n  B --> C[Gamma]"
    val id     = Library.createDiagram("automatic flowchart", source)
    Library.storeDiagram(id, DiagramSnapshot.minimal(Some(source)).copy(metadata = DiagramMetadata(hiddenElements = Set("node:C"))))
    drawCards(Seq(id)): cards =>
      val text = cards.head.querySelectorAll("g.node").toSeq.map(_.textContent).mkString(", ")
      check(text.contains("Alpha") && text.contains("Beta"), s"precondition: the card does not show the visible nodes: $text")
      check(!text.contains("Gamma"), s"the card shows the hidden node Gamma: $text")

  // ------------------------------------------------------------------ images next to a desktop file

  /** A blob URL of a PNG of 2 × 1 pixels: the asset URL that the fake shell gives for an image. */
  private def pngUrl(): String =
    val bytes = org.jpablo.graphexplorer.viewer.components.svgCanvas.CanvasDropSpec
      .bytesOf(org.jpablo.graphexplorer.viewer.components.svgCanvas.CanvasPasteSpec.Png2x1Base64)
    val blob = js.Dynamic.newInstance(js.Dynamic.global.Blob)(js.Array(bytes), js.Dynamic.literal(`type` = "image/png"))
    js.Dynamic.global.URL.createObjectURL(blob).asInstanceOf[String]

  private def hrefs(card: dom.Element): Seq[String] =
    card.querySelectorAll("image").toSeq.collect { case e: dom.Element => e }.map: e =>
      Option(e.getAttribute("href"))
        .orElse(Option(e.getAttributeNS("http://www.w3.org/1999/xlink", "href")))
        .getOrElse("")

  /** A desktop file with an image node, whose image is in the assets folder next to the file (as a paste makes it). */
  private def imageCard(name: String, layout: Option[DiagramLayout]): Future[Unit] =
    val shell = FakeDesktopShell()
    shell.install()
    val diagram = s"/w/$name.dot"
    val image   = s"/w/$name.assets/a.png"
    val url     = pngUrl()
    shell.files(diagram) =
      s"digraph G {\n  pic [shape=box, penwidth=0, label=\"\", fixedsize=true, width=0.0278, height=0.0139, image=\"$name.assets/a.png\", imagescale=true]\n  pic -> b\n}"
    layout.foreach(l => shell.layouts(diagram) = DiagramLayout.render(l))
    shell.images(image) = org.jpablo.graphexplorer.viewer.components.svgCanvas.CanvasPasteSpec.Png2x1Base64
    shell.assetUrls(image) = url
    shell.putEntry(LibraryEntry(diagram, DiagramMetadata(), 2, 2))
    DesktopLibrary
      .load()
      .flatMap: library =>
        Library.install(library)
        drawCards(library.indexNow().diagrams.map(_.id)): cards =>
          val shown = hrefs(cards.head)
          check(shown.contains(url), s"the card does not show the image of $image from its asset URL $url: the images are ${shown.mkString(", ")}")
      .transform: result =>
        shell.uninstall()
        result

  test("the card of an automatic desktop file shows the image in the assets folder next to the file"):
    imageCard("automatic-image", None)

  test("the card of a desktop file in the manual mode shows the image in the assets folder next to the file"):
    imageCard(
      "manual-image",
      Some(DiagramLayout(manual = true).placed(Map("pic" -> LayoutPoint(0, 0), "b" -> LayoutPoint(200, 0))))
    )
