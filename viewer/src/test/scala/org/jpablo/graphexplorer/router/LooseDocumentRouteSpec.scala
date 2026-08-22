package org.jpablo.graphexplorer.router

import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.Promise
import scala.scalajs.js

/** Phase 2 item 2: a loose file has a route, so it has a destination.
  *
  * The route holds an opaque session id, and holds no path (§13). A route that
  * held the path would put that path in the URL and in the browser history.
  */
class LooseDocumentRouteSpec extends FunSuite with TestHelpers:

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

  test("a /documents/<id> path parses to a loose-document route") {
    stubWindow()
    dom.window.history.pushState(null, "", "/documents/doc-abc123")

    given Owner = unsafeWindowOwner

    val done   = Promise[Unit]()
    val router = Router()

    router.currentRoute.foreach:
      case Route.LooseDocument(sessionId) =>
        assertEquals(sessionId, "doc-abc123")
        done.trySuccess(())
      case other =>
        done.tryFailure(new Exception(s"Unexpected route: $other"))

    done.future
  }

  test("navigating to a loose document writes /documents/<id> and no path") {
    stubWindow()
    dom.window.history.pushState(null, "", "/")

    given Owner = unsafeWindowOwner

    val router = Router()
    router.navigateTo(Route.LooseDocument("doc-abc123"))

    assertEquals(dom.window.location.pathname, "/documents/doc-abc123")
  }
