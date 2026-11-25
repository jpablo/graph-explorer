package org.jpablo.graphexplorer.viewer.desktop

import org.scalajs.dom

import scala.scalajs.js
import scala.util.{Failure, Success}

/** Opens a link of a diagram in the browser of the system.
  *
  * A diagram can hold any URL, and a diagram can come from any location. Thus only an http or an https link opens. A
  * `javascript:` or a `file:` link does not open.
  *
  * In the desktop app, the page asks the shell with the `open_external` command, because the app window must stay on
  * the diagram. The shell checks the URL again. On the web, the page opens the link in a new tab.
  */
object ExternalLink:

  val OpenExternal = "open_external"

  /** True for an absolute http or https URL with no space and no control character. `is_openable_url` in `main.rs`
    * has the same rule.
    */
  def isOpenable(url: String): Boolean =
    val lower = url.toLowerCase
    val rest  = List("https://", "http://").find(lower.startsWith).map(p => url.drop(p.length))
    rest.exists(_.nonEmpty) && !url.exists(c => c.isWhitespace || c.isControl)

  /** Open `url` in the browser of the system. A URL that is not http or https does not open. */
  def open(url: String): Unit =
    if !isOpenable(url) then dom.console.warn(s"Graph Explorer opens only an http or https link, not: $url")
    else if DesktopIpc.available then
      given scala.concurrent.ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue
      DesktopIpc
        .invoke(OpenExternal, js.Dynamic.literal(url = url))
        .onComplete:
          case Success(_)     => ()
          case Failure(error) => dom.console.error(s"The link did not open: $url", error.getMessage)
    else
      // "noopener" gives the new tab no handle on the app.
      dom.window.open(url, "_blank", "noopener,noreferrer")
      ()
