package org.jpablo.graphexplorer.viewer.utils

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.state.ProjectId
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.utils.ShareUrl
import scala.scalajs.js

class ShareUrlSpec extends FunSuite:

  /** The desktop shell installs `window.__TAURI__.core.invoke`
    * (`withGlobalTauri`). This stub makes the page think that it runs inside
    * the shell. The test page keeps its own origin, and that origin plays the
    * part of the webview origin (`tauri://localhost`, or
    * `http://tauri.localhost` on Windows).
    */
  private def stubDesktop(): Unit =
    js.eval("window.__TAURI__ = { core: { invoke: function() { return Promise.resolve(null); } } };")

  override def afterEach(context: AfterEach): Unit =
    js.eval("delete window.__TAURI__;")

  private val dot   = "digraph G { a -> b }"
  private val query = s"?${ShareUrl.param}=" + js.URIUtils.encodeURIComponent(dot)

  test("in the desktop app, a share link goes to the public web app, not to the webview origin") {
    stubDesktop()

    val url = ShareUrl.buildForProject(ProjectId("abc123"), dot)

    // Nobody else can open the webview origin. The link must name the site.
    assertEquals(url, s"https://graph-explorer.net/diagrams/abc123$query")
  }

  test("on the web, a share link keeps the origin of the page") {
    // Local dev and previews must link to themselves, not to the live site.
    val url = ShareUrl.buildForProject(ProjectId("abc123"), dot)

    assertEquals(url, s"${dom.window.location.origin}/diagrams/abc123$query")
  }

  test("buildForProject encodes DOT and can be read back") {
    // Minimal window/location stub for tests
    js.eval(
      """
        if (typeof window === 'undefined') { global.window = {}; }
        if (typeof window.location === 'undefined') {
          window.location = { origin: 'http://localhost', search: '', pathname: '/', href: 'http://localhost/' };
        }
      """
    )

    val pid = ProjectId("abc123")
    val dot = "digraph G {\n  a -> b;\n  label=\"A & B\";\n}"

    val url = ShareUrl.buildForProject(pid, dot)

    // Extract query part and read it back via URLSearchParams
    val query = url.dropWhile(_ != '?')
    val params = new dom.URLSearchParams(query)
    val decoded = Option(params.get(ShareUrl.param))

    assertEquals(decoded, Some(dot))
  }
