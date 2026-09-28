package org.jpablo.graphexplorer.router

import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The library lives at `/app`.
  *
  * On the web, `/` is the product page, which Netlify serves from a separate
  * file. The app must therefore write `/app` for the library, or a reload of the
  * library would show the product page. The desktop still loads `/`, so `/` must
  * still open the library.
  */
class HomeRouteSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  private def stubWindow(): Unit =
    js.eval(
      """
        if (typeof window === 'undefined') { global.window = {}; }
        if (typeof window.location === 'undefined') {
          window.location = { origin: 'http://localhost', search: '', pathname: '/', href: 'http://localhost/' };
        }
        window.history = {
          pushState: function(_,__,url) {
            var a = new URL(url, 'http://localhost');
            window.location.pathname = a.pathname;
            window.location.search = a.search;
            window.location.href = a.href;
          }
        };
      """
    )

  private def routeAt(path: String): Future[Route] =
    stubWindow()
    dom.window.history.pushState(null, "", path)

    given Owner = unsafeWindowOwner

    val result = Promise[Route]()
    Router().currentRoute.foreach(result.trySuccess)
    result.future

  test("/app parses to the library") {
    routeAt("/app").map(route => assertEquals(route, Route.Home))
  }

  test("/app/ parses to the library") {
    routeAt("/app/").map(route => assertEquals(route, Route.Home))
  }

  test("/ still parses to the library, because the desktop loads it") {
    routeAt("/").map(route => assertEquals(route, Route.Home))
  }

  test("navigating to the library writes /app") {
    stubWindow()
    dom.window.history.pushState(null, "", "/diagrams/abc")

    given Owner = unsafeWindowOwner

    val router = Router()
    router.navigateTo(Route.Home)

    assertEquals(dom.window.location.pathname, "/app")
  }

  test("Router.homePath is the path the router writes for the library") {
    assertEquals(Router.homePath, "/app")
  }
