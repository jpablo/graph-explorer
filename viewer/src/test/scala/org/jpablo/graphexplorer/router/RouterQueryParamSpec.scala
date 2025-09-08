package org.jpablo.graphexplorer.router

import munit.FunSuite
import com.raquo.airstream.ownership.Owner
import com.raquo.laminar.api.L.unsafeWindowOwner
import org.scalajs.dom
import scala.scalajs.js
import scala.concurrent.Promise
import org.jpablo.graphexplorer.viewer.utils.ShareUrl

class RouterQueryParamSpec extends FunSuite:

  test("Router picks up ?dot= param for ProjectDetail") {
    // Minimal window/location/history stub for tests
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
    // Arrange: set URL before creating Router
    dom.window.history.pushState(null, "", s"/diagrams/xyz789?${ShareUrl.param}=" + js.URIUtils.encodeURIComponent("digraph G { x -> y }") )

    given Owner = unsafeWindowOwner

    val p = Promise[Unit]()
    val router = Router()

    router.currentRoute.foreach { route =>
      route match
        case Route.ProjectDetail(id, source) =>
          assertEquals(id, "xyz789")
          assertEquals(source, Some("digraph G { x -> y }"))
          p.trySuccess(())
        case other => p.tryFailure(new Exception(s"Unexpected route: $other"))
    }

    p.future
  }
