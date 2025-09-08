package org.jpablo.graphexplorer.viewer.utils

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.state.ProjectId
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.utils.ShareUrl
import scala.scalajs.js

class ShareUrlSpec extends FunSuite:

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
