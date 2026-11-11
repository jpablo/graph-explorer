package org.jpablo.graphexplorer.library

import munit.FunSuite
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.models.DiagramId
import org.jpablo.graphexplorer.viewer.state.DiagramSnapshot
import org.jpablo.graphexplorer.viewer.utils.{ShareUrl, TestHelpers}
import org.scalajs.dom
import upickle.default.read

import scala.scalajs.js

/** The start of the web app on a share URL (`/diagrams/<id>?dot=...`).
  *
  * The app goes to the diagram of the link, and the share URL must not stay in
  * the browser history. With a new history entry, Back went to the share URL
  * again. The route of that URL has the old text of the link, and a new viewer
  * of the stored diagram started with that text.
  */
class ShareLinkOpenSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "ShareLinkOpenSpec"

  override def munitFixtures = List(mockStorageFixture())

  private var startHref = ""

  override def beforeEach(context: BeforeEach): Unit =
    startHref = dom.window.location.href

  override def afterEach(context: AfterEach): Unit =
    js.eval("delete window.history.pushState;")
    dom.window.history.replaceState(null, "", startHref)

  private def payloadKey(id: DiagramId): String =
    s"[StoredString]graph-explorer.project.${id.value}"

  private def storedSource(id: DiagramId): Option[String] =
    Option(dom.window.localStorage.getItem(payloadKey(id))).map(read[DiagramSnapshot](_).source)

  private def shareUrl(id: String, dot: String): String =
    s"/diagrams/$id?${ShareUrl.param}=" + js.URIUtils.encodeURIComponent(dot)

  /** Count the history entries that the page adds from now. A count of
    * `history.length` stops at the limit of the browser (50 entries).
    */
  private def countPushes(): () => Int =
    js.eval(
      """
        window.__sharePushes = 0;
        window.__sharePushState = History.prototype.pushState;
        window.history.pushState = function(s, t, u) {
          window.__sharePushes += 1;
          return window.__sharePushState.call(window.history, s, t, u);
        };
      """
    )
    () => js.Dynamic.global.window.__sharePushes.asInstanceOf[Int]

  private def routeOf(router: Router): Route =
    router.currentRoute.observe(using com.raquo.laminar.api.L.unsafeWindowOwner).now()

  test("a share link to a stored diagram replaces the share URL in the history"):
    // Finding 6. Before the fix, `startApp` went to /diagrams/<id> with
    // `pushState`, so the share URL stayed as the previous history entry.
    // Then this test failed at the first check: the redirect added one entry.
    val text = "digraph G {\n  stored -> text\n}"
    val id   = BrowserLibrary.createDiagram("Mine", text)
    dom.window.history.replaceState(null, "", shareUrl(id.value, "digraph G {\n  old -> link\n}"))
    val router = Router()
    same(routeOf(router), Route.Diagram(id.value, Some("digraph G {\n  old -> link\n}")), "precondition: the start route")
    val pushes = countPushes()
    LibraryNavigation.openShareLink(router, "digraph G {\n  old -> link\n}")
    same(pushes(), 0, "the redirect from the share URL added a history entry")
    same(dom.window.location.pathname, s"/diagrams/${id.value}", "the redirect did not go to the diagram of the link")
    same(dom.window.location.search, "", "the URL still has the text of the link")
    same(routeOf(router), Route.Diagram(id.value), "the route is not the stored diagram")
    same(storedSource(id), Some(text), "the redirect changed the stored diagram")

  test("a share link to a diagram that is not in the library stores the text of the link at once"):
    // Finding 6, with the visit fix. A visit with no edit writes nothing now.
    // Before, `startApp` made an index entry with no payload, and the visit
    // wrote the text of the link by accident. Before the fix, this test
    // failed: the new diagram had no payload, and the redirect added an entry.
    val dot = s"digraph G {\n  shared -> link${js.Date.now().toLong}\n}"
    dom.window.history.replaceState(null, "", shareUrl("not-in-this-library", dot))
    val router = Router()
    val pushes = countPushes()
    LibraryNavigation.openShareLink(router, dot)
    val created = BrowserLibrary.findDiagramBySource(dot)
    check(created.isDefined, "the new diagram has no payload with the text of the link")
    created.foreach: id =>
      same(routeOf(router), Route.Diagram(id.value, Some(dot)), "the route is not the new diagram")
      same(BrowserLibrary.diagramExists(id), true, "the library index has no entry for the new diagram")
    same(pushes(), 0, "the redirect from the share URL added a history entry")
    same(dom.window.location.search, "", "the URL still has the text of the link")
