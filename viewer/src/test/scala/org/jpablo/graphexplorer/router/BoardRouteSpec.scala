package org.jpablo.graphexplorer.router

import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api.L.unsafeWindowOwner
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.Promise
import scala.scalajs.js

/** A board has a route, so that a reload and a link show the board again (ADR 0003). */
class BoardRouteSpec extends FunSuite with TestHelpers:

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

  test("a /boards/<id> path parses to a board route"):
    stubWindow()
    dom.window.history.pushState(null, "", "/boards/board-abc123")

    given Owner = unsafeWindowOwner

    val done   = Promise[Unit]()
    val router = Router()

    router.currentRoute.foreach:
      case Route.Board(id) =>
        assertEquals(id, "board-abc123")
        done.trySuccess(())
      case other =>
        done.tryFailure(new Exception(s"Unexpected route: $other"))

    done.future

  test("a navigation to a board writes /boards/<id>"):
    stubWindow()
    dom.window.history.pushState(null, "", "/")

    given Owner = unsafeWindowOwner

    val router = Router()
    router.navigateTo(Route.Board("board-abc123"))

    assertEquals(dom.window.location.pathname, "/boards/board-abc123")
