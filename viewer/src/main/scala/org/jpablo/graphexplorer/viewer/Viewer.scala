package org.jpablo.graphexplorer.viewer

import viewerbuildinfo.BuildInfo
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.library.{DesktopStartup, Library, LibraryNavigation}
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.components.{RouterCommands, resolveTheme}
import org.jpablo.graphexplorer.viewer.desktop.{DesktopBridge, DesktopIpc, DesktopOpenRequests, LiveViewCommands}
import org.jpablo.graphexplorer.viewer.logging.Level
import org.scalajs.dom.{document, window, URLSearchParams}
import org.jpablo.graphexplorer.viewer.models.ClientSize
import org.jpablo.graphexplorer.viewer.utils.ShareUrl

import scala.scalajs.js.Date
import scala.concurrent.ExecutionContext.Implicits.global

object Viewer:

  def main(args: Array[String]): Unit =
    given Owner    = unsafeWindowOwner
    val errors     = setupErrorHandling()
    val infos      = EventBus[String]()
    val router     = Router()
    // A failure of an async desktop command (a new file, an open) goes to the
    // alert of the view and to the notice of the home page.
    val routerCmds = RouterCommands(
      router,
      message =>
        errors.emit(message)
        Library.showNotice(Vector(message))
    )


    def setTheme(theme: String): Unit =
      document.documentElement.setAttribute("data-theme", theme)

    // Clipboard writes with failure surfaced: the returned Promise was previously
    // discarded, so a denied permission / unfocused document / insecure origin failed
    // with no feedback while the UI still suggested success.
    def clipboardWrite(text: String): Unit =
      try
        window.navigator.clipboard
          .writeText(text)
          .`then`[Unit](
            (_: Unit) => (),
            (err: Any) =>
              errors.emit(s"Could not copy to clipboard: ${String.valueOf(err)}")
          )
      catch
        case e: Throwable =>
          errors.emit(s"Clipboard unavailable: ${e.getMessage}")

    // The read side. A rejected promise (denied permission, no user gesture,
    // insecure origin) has to reach the caller as a failed Future: the paste
    // command reports it, and must not mistake it for an empty clipboard.
    def clipboardRead(): scala.concurrent.Future[String] =
      try window.navigator.clipboard.readText().toFuture
      catch case e: Throwable => scala.concurrent.Future.failed(e)

    val viewerSettings = Library.loadViewerSettings()
    // Unconditionally, not `foreach`: with nothing stored the app has to land on
    // the default theme, and a stored theme we no longer ship has to fall back
    // to it rather than leaving `data-theme` pointing at absent CSS.
    setTheme(resolveTheme(viewerSettings.now().currentTheme))

    // Determine ClientSize based on viewport width
    val mediaQueryList = window.matchMedia("(max-width: 768px)")
    val clientSize     = if (mediaQueryList.matches) ClientSize.Small else ClientSize.Normal

    // Parse log level from query string
    val queryParams = new URLSearchParams(window.location.search)
    val logLevel = Option(queryParams.get("logLevel"))
      .map(Level.fromString)
      .getOrElse(Level.None)

    // On the desktop, the library is the metadata store on disk (ADR 0001),
    // so it has to be in place BEFORE anything reads it. The start runs the
    // one-time migrations first (`DesktopStartup`). On the web there is
    // nothing to wait for.
    val libraryReady: scala.concurrent.Future[Unit] =
      if !DesktopIpc.available then scala.concurrent.Future.successful(())
      else DesktopStartup.run().map(Library.install)

    libraryReady.foreach: _ =>
      startApp()

    def startApp(): Unit =
      // If a share URL (?dot=...) is present, resolve it immediately. Only
      // the web app gets a share URL, and only the browser library keeps the
      // source of a diagram.
      val sharedDot = ShareUrl.readDotParam().filter(_ => !Library.isDesktop)
      sharedDot.foreach(LibraryNavigation.openShareLink(router, _))

      // Before any route is chosen: a socket client can ask "what is selected"
      // while the app is on its library page. The answer must be "nothing is
      // open", not a timeout (D7.2: the limit of the live view tier is the
      // absence of a view, and that is an answer).
      LiveViewCommands.install()

      // Before `DesktopOpenRequests.install` below, which announces
      // `viewer_ready` and releases the shell's queued opens. A `gx open` of a
      // FILE delivers the document first and asks the page to display it
      // second, so the document listener has to exist before either arrives.
      DesktopBridge.install()

      // Installed at the WINDOW level, not per view: an open request routinely
      // arrives while the app is on Home, which is precisely when there is no
      // viewer to deliver it to.
      //
      // And installed HERE rather than at the top of main, because installing
      // announces `viewer_ready` — the signal the shell waits on before
      // delivering an open. Announcing it before `Library.install` had run
      // would let an early open be answered "no such diagram" by a library that
      // had simply not loaded yet.
      DesktopOpenRequests.install(router)

      Graphviz.build().foreach: (graphviz: Graphviz) =>
        dom.console.log("Graphviz initialized (Scala port for dot, viz-js for other engines):", graphviz)
        printBanner()
        // Start the app after Graphviz is initialized
        val app =
          AppRoutes(
            AppServices(
              graphviz = graphviz,
              router = router,
              routerCmds = routerCmds,
              errors = errors,
              infos = infos,
              viewerSettings = viewerSettings,
              setTheme = setTheme,
              clipboardWrite = clipboardWrite,
              clipboardRead = () => clipboardRead(),
              clientSize = clientSize,
              logLevel = logLevel
            )
          )
        render(document.querySelector("#app"), app)

  private def setupErrorHandling()(using Owner): EventBus[String] =
    val errors = EventBus[String]()
    AirstreamError.registerUnhandledErrorCallback(ex => errors.emit(ex.getMessage))
    windowEvents(_.onError).foreach(e => errors.emit(e.message))
    errors.events.foreach(e => dom.console.error("Error:", e))
    // debug focus events
    document.addEventListener("focusin", e => dom.console.debug("focusin:", e.target))
    document.addEventListener("focusout", e => dom.console.debug("focusout:", e.target))
    errors

  private def printBanner() =
    val banner =
      s"""
        |Welcome to Graph Explorer!
        |------------------------------
        |version:      ${BuildInfo.version}
        |scalaVersion: ${BuildInfo.scalaVersion}
        |sbtVersion:   ${BuildInfo.sbtVersion}
        |builtAt:      ${new Date(BuildInfo.builtAtString).toUTCString}
        |""".stripMargin
    dom.console.info(banner)
