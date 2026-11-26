package org.jpablo.graphexplorer.viewer.desktop

import org.jpablo.graphexplorer.viewer.utils.{FileLinks, Platform}
import org.scalajs.dom

import scala.scalajs.js
import scala.util.{Failure, Success}

/** Opens a link of a diagram: a web page in the browser of the system, or on the desktop, a local file.
  *
  * A diagram can hold any URL, and a diagram can come from any location. Thus only an http or an https link opens
  * everywhere. A `javascript:` link never opens.
  *
  * In the desktop app, the page asks the shell with the `open_external` command, because the app window must stay on
  * the diagram. The shell checks the URL again. On the web, the page opens the link in a new tab.
  *
  * On the desktop, a local file link (a `file://` URL, or a path relative to the diagram) also opens, with the
  * `open_file_link` command. The shell opens a document with its default app. A file that can run a program (an app,
  * a script, an installer) does not open: the shell shows it in the file manager (`open_action` in `local_files.rs`).
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

  /** True when this page can open `url`: a web link, or on the desktop, a local file link. */
  def isOpenableHere(url: String): Boolean =
    isOpenable(url) || (DesktopIpc.available && FileLinks.isLocalFileLink(url))

  /** Open `url`. A relative link is relative to the folder of the diagram at `diagramPath`. `info` gets a message for
    * the person when the shell shows the file in the file manager, and `error` when the link does not open.
    */
  def open(
      url:         String,
      diagramPath: Option[String] = None,
      info:        String => Unit = _ => (),
      error:       String => Unit = _ => ()
  ): Unit =
    given scala.concurrent.ExecutionContext = scala.scalajs.concurrent.JSExecutionContext.queue
    if isOpenable(url) then
      if DesktopIpc.available then
        DesktopIpc
          .invoke(OpenExternal, js.Dynamic.literal(url = url))
          .onComplete:
            case Success(_)     => ()
            case Failure(error) => dom.console.error(s"The link did not open: $url", error.getMessage)
      else
        // "noopener" gives the new tab no handle on the app.
        dom.window.open(url, "_blank", "noopener,noreferrer")
        ()
    else if DesktopIpc.available && FileLinks.isLocalFileLink(url) then
      val name = DesktopFiles.baseName(url)
      DesktopFiles
        .openFileLink(diagramPath, url)
        .onComplete:
          case Success("revealed") =>
            val manager = if Platform.isMac then "Finder" else "the file manager"
            info(s"$name can run a program. Graph Explorer shows it in $manager, and does not open it.")
          case Success(_)       => ()
          case Failure(failure) => error(s"The link did not open: ${failure.getMessage}")
    else dom.console.warn(s"Graph Explorer opens only an http or https link, or a file on the desktop, not: $url")
