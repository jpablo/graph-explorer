package org.jpablo.graphexplorer.viewer

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.jpablo.graphexplorer.library.{Library, LoggedChecks}
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.RouterCommands
import org.jpablo.graphexplorer.viewer.logging.Level
import org.jpablo.graphexplorer.viewer.models.{ClientSize, DiagramId}
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** A click on a diagram in the Library keeps the Library on the screen.
  *
  * Before, each diagram route built a new `TopLevel`, with a new Library panel.
  * The list lost its scroll position, and the whole UI was built again. Now a
  * workspace stays mounted for all the diagram routes. Only the parts of one
  * diagram (the canvas, the source panel, the dialogs) are built again.
  *
  * The test renders the real pages of the app (`AppRoutes`) with a web library
  * of many diagrams, and clicks the Library as a person does.
  */
class LibrarySwitchSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "LibrarySwitchSpec"

  override def munitFixtures = List(mockStorageFixture())

  /** Enough diagrams to make the list scroll in a box of 600 px. */
  private val fillers = 30

  private final class App(
      val box:    dom.html.Element,
      val router: Router,
      val idA:    DiagramId,
      root:       RootNode,
      startHref:  String
  ):
    def leftPanel: dom.Element = box.querySelector("#left-panel")

    /** The element of the Library list that scrolls. */
    def listScroller: dom.html.Element =
      leftPanel.querySelector("div.overflow-y-auto").asInstanceOf[dom.html.Element]

    /** The link of the Library row with the name `name`. */
    def row(name: String): Option[dom.html.Element] =
      Option(leftPanel)
        .toSeq
        .flatMap(_.querySelectorAll("li > a").toSeq)
        .collectFirst { case a: dom.html.Element if a.textContent.trim == name => a }

    def canvasText: String =
      Option(box.querySelector("#canvas-container svg")).map(_.textContent).getOrElse("")

    def toolbar: dom.Element = box.querySelector("#toolbar")

    /** The item of a toolbar menu with the label `label`. The items of a
      * dropdown are always in the DOM.
      */
    def toolbarMenuItem(label: String): Option[dom.html.Element] =
      Option(toolbar)
        .toSeq
        .flatMap(_.querySelectorAll("ul.menu li > a").toSeq)
        .collectFirst { case a: dom.html.Element if a.querySelector("span") != null && a.querySelector("span").textContent.trim == label => a }

    /** The names of the canvas nodes that have the class `selected`. A Graphviz
      * node has its name in `<title>`, and the label again in `<text>`.
      */
    def selectedNodes: Set[String] =
      box
        .querySelectorAll("#canvas-container svg g.node.selected")
        .toSeq
        .map(n => Option(n.querySelector("title")).map(_.textContent.trim).getOrElse(""))
        .toSet

    def close(): Unit =
      root.unmount()
      box.parentNode.removeChild(box)
      dom.window.history.replaceState(null, "", startHref)

  /** Two diagrams with known nodes, and fillers below them in the list. The
    * list shows the newest diagram first, so the fillers come first in time.
    */
  private def makeLibrary(): (DiagramId, DiagramId) =
    (1 to fillers).foreach(i => Library.createDiagram(s"Filler $i", s"digraph F$i { f$i; }"))
    val a = Library.createDiagram("Switch A", "digraph A { alpha1 -> alpha2; }")
    val b = Library.createDiagram("Switch B", "digraph B { beta1 -> beta2; }")
    (a, b)

  private def withApp(body: App => Future[Unit]): Future[Unit] =
    val startHref = dom.window.location.href
    Library.loadViewerSettings().update(_.copy(leftPanelVisible = true))
    val (a, _) = makeLibrary()
    withGraphvizAsync: graphviz =>
      val router = Router()
      router.navigateTo(Route.Diagram(a.value))
      val errors = EventBus[String]()
      val services = AppServices(
        graphviz = graphviz,
        router = router,
        routerCmds = RouterCommands(router, _ => ()),
        errors = errors,
        infos = EventBus[String](),
        viewerSettings = Library.loadViewerSettings(),
        setTheme = _ => (),
        clipboardWrite = _ => (),
        clipboardRead = () => Future.successful(""),
        clientSize = ClientSize.Normal,
        logLevel = Level.None
      )
      val box = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      box.style.width = "1200px"
      box.style.height = "600px"
      box.style.display = "flex"
      box.style.setProperty("flex-direction", "column")
      dom.document.body.appendChild(box)
      val app = App(box, router, a, render(box, AppRoutes(services)), startHref)
      Waits
        .settle(app.row("Switch A").isDefined && app.canvasText.contains("alpha1"), timeoutMs = 10000)
        .flatMap: _ =>
          check(app.row("Switch A").isDefined, "precondition: the Library shows no row 'Switch A'")
          check(app.canvasText.contains("alpha1"), "precondition: the canvas does not show the diagram 'Switch A'")
          body(app)
        .transform: outcome =>
          app.close()
          outcome

  test("a click on a diagram in the Library keeps the Library panel and its scroll position"):
    withApp: app =>
      val panel    = app.leftPanel
      val scroller = app.listScroller
      // The test page has no Tailwind CSS, so the list has no height limit.
      // A fixed height makes it scroll, as it does in the app.
      scroller.style.height = "150px"
      scroller.style.overflowY = "auto"
      check(
        scroller.scrollHeight > scroller.clientHeight,
        s"precondition: the Library list does not scroll (${scroller.scrollHeight} <= ${scroller.clientHeight})"
      )
      scroller.scrollTop = 120
      val scrolled = scroller.scrollTop
      check(scrolled > 0, "precondition: the Library list did not scroll")
      app.row("Switch B").foreach(_.click())
      Waits
        .settle(app.canvasText.contains("beta1"), timeoutMs = 10000)
        .map: _ =>
          check(app.canvasText.contains("beta1"), "the canvas does not show the diagram 'Switch B' after the click")
          check(app.leftPanel eq panel, "the click built a new Library panel (#left-panel is a new DOM node)")
          check(app.listScroller eq scroller, "the click built a new Library list")
          check(
            app.listScroller.scrollTop == scrolled,
            s"the Library list scrolled from $scrolled to ${app.listScroller.scrollTop}"
          )
          check(
            app.row("Switch B").exists(_.classList.contains("gx-row-active")),
            "the row 'Switch B' is not marked as the open diagram"
          )
          check(
            app.row("Switch A").exists(r => !r.classList.contains("gx-row-active")),
            "the row 'Switch A' is still marked as the open diagram"
          )

  test("after a visit to the library page, the Library opens a diagram again"):
    withApp: app =>
      app.router.navigateTo(Route.Home)
      Waits
        .settle(app.leftPanel == null, timeoutMs = 5000)
        .flatMap: _ =>
          check(app.leftPanel == null, "precondition: the library page shows the Library panel of a diagram")
          app.router.navigateTo(Route.Diagram(app.idA.value))
          Waits.settle(app.row("Switch B").isDefined && app.canvasText.contains("alpha1"), timeoutMs = 10000)
        .flatMap: _ =>
          check(app.row("Switch B").isDefined, "precondition: the Library is not on the screen again")
          app.row("Switch B").foreach(_.click())
          Waits.settle(app.canvasText.contains("beta1"), timeoutMs = 10000)
        .map: _ =>
          check(app.canvasText.contains("beta1"), "after the visit to the library page, a click on 'Switch B' did not open it")
          check(
            app.row("Switch B").exists(_.classList.contains("gx-row-active")),
            "after the visit to the library page, the row 'Switch B' is not marked as open"
          )

  test("a click in the Library keeps the toolbar, and the toolbar acts on the new diagram"):
    withApp: app =>
      val toolbar = app.toolbar
      check(toolbar != null, "precondition: the page shows no #toolbar")
      app.row("Switch B").foreach(_.click())
      Waits
        .settle(app.canvasText.contains("beta1"), timeoutMs = 10000)
        .flatMap: _ =>
          check(app.canvasText.contains("beta1"), "the canvas does not show the diagram 'Switch B' after the click")
          check(app.toolbar eq toolbar, "the click built a new toolbar (#toolbar is a new DOM node)")
          check(
            app.toolbar.textContent.contains("Switch B"),
            s"the toolbar does not show the title 'Switch B': '${app.toolbar.textContent.take(200)}'"
          )
          val selectAll = app.toolbarMenuItem("Select all nodes")
          check(selectAll.isDefined, "precondition: the toolbar has no menu item 'Select all nodes'")
          selectAll.foreach(_.click())
          Waits.settle(app.selectedNodes.nonEmpty, timeoutMs = 5000)
        .map: _ =>
          check(
            app.selectedNodes == Set("beta1", "beta2"),
            s"'Select all nodes' in the toolbar did not select the nodes of 'Switch B': ${app.selectedNodes}"
          )
