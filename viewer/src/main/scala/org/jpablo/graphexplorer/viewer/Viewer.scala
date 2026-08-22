package org.jpablo.graphexplorer.viewer

import buildinfo.BuildInfo
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.projects.{DesktopLibrary, DesktopMigration, Library, ProjectsDirectoryView}
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.backends.graphviz.{DotExamples, Graphviz}
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands, TopLevel, resolveTheme}
import org.jpablo.graphexplorer.viewer.desktop.{DesktopBridge, DesktopIpc, DesktopOpenRequests, SessionCommands}
import org.jpablo.graphexplorer.viewer.logging.Level
import org.jpablo.graphexplorer.viewer.state.{PersistedDiagramState, ProjectId, RightPanelSection, ViewerState}
import org.scalajs.dom.{document, window, URLSearchParams}
import org.jpablo.graphexplorer.viewer.models.ClientSize
import org.jpablo.graphexplorer.viewer.utils.ShareUrl
import org.jpablo.graphexplorer.viewer.widgets.{Button, primary, small, tiny}

import scala.scalajs.js
import scala.scalajs.js.Date
import scala.concurrent.ExecutionContext.Implicits.global

object Viewer:

  def main(args: Array[String]): Unit =
    given Owner    = unsafeWindowOwner
    val errors     = setupErrorHandling()
    val infos      = EventBus[String]()
    val router     = Router()
    val routerCmds = RouterCommands(router)

    // Installed once, at the window level rather than per view: an open request
    // routinely arrives while the app is on Home, which is precisely when there
    // is no viewer to deliver it to.
    DesktopOpenRequests.install(router.navigateTo)

    var lastRightPanelSection = RightPanelSection.none
    var lastLeftPanelVisible  = false

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

    // D7.3: on the desktop the library IS the store on disk, so it has to be
    // in place BEFORE anything reads it — the share-URL branch below asks
    // `projectExists` before a single route renders, and answering that from
    // the browser library and then swapping backends would open the wrong
    // diagram. On the web there is nothing to wait for.
    val libraryReady: scala.concurrent.Future[Unit] =
      if !DesktopIpc.available then scala.concurrent.Future.successful(())
      else
        for
          onDisk  <- DesktopLibrary.load()
          _       <- DesktopMigration.runOnce(onDisk.map(_.id.value).toSet)
          // Re-read rather than reuse `onDisk`: the migration may have just
          // added records, and starting with a mirror that omits them would
          // show an empty library until the first external change.
          records <- DesktopLibrary.load()
        yield
          Library.install(DesktopLibrary(records))
          // Writes are debounced, so edits can exist only in memory. A closing
          // window must not take them with it — `localStorage` never needed
          // this because it wrote on the spot.
          val flush: js.Function1[dom.Event, Unit] = _ => Library.flush()
          dom.window.addEventListener("pagehide", flush)
          dom.window.addEventListener("blur", flush)

    libraryReady.foreach: _ =>
      startApp()

    def startApp(): Unit =
      // If a share URL (?dot=...) is present, resolve it immediately:
      val sharedDot = ShareUrl.readDotParam()
      sharedDot.foreach: dot =>
        // The link path embeds the project id (/diagrams/<id>?dot=...): when that project
        // exists locally, open IT — matching by exact source alone forked a new "Untitled"
        // copy every time the owner revisited their own link after an edit.
        ShareUrl.readProjectIdFromPath().filter(Library.projectExists) match
          case Some(pathId) =>
            router.navigateTo(Route.ProjectDetail(pathId.value))
          case None =>
            Library.findProjectByExactSource(dot) match
              case Some(existingId) =>
                router.navigateTo(Route.ProjectDetail(existingId.value))
              case None =>
                // Create a new project initialized with the provided DOT
                val newId = Library.createProjectDirectoryEntry(PersistedDiagramState.defaultProjectName)
                router.navigateTo(Route.ProjectDetail(newId.value, Some(dot)))

      // Before any route is chosen: a session client asking "what is selected"
      // while the app sits on its library page deserves "nothing is open", not a
      // timeout (D7.2 — the session tier's limit is the absence of a view, and
      // that is an answer).
      SessionCommands.install()

      Graphviz.build().foreach: (graphviz: Graphviz) =>
        dom.console.log("Graphviz initialized (Scala port for dot, viz-js for other engines):", graphviz)
        printBanner()
        // Start the app after Graphviz is initialized

        /** The diagram page. `exampleName` is what makes it a read-only visit: it
          * turns off persistence and puts the "copy to my library" strip on top.
          * Both routes share this so an example is the SAME viewer, not a
          * second, weaker one.
          */
        def diagramView(id: String, source: Option[String], exampleName: Option[String]) =
          // Owner scoped to this project visit: killed when the view unmounts, so the
          // ViewerState's subscriptions (phases, persistence, theme, panels...) — and the
          // whole object graph they retain — are released instead of leaking one full
          // ViewerState per navigation on the never-killed window owner.
          val viewOwner = new ManualOwner
          val state =
            ViewerState(
              projectId = ProjectId(id),
              graphviz = graphviz,
              writeText = clipboardWrite,
              readText = clipboardRead,
              setTheme = setTheme,
              errorBus = errors,
              infoBus = infos,
              initialSource = source,
              initialRightPanelSection = lastRightPanelSection,
              initialLeftPanelVisible = lastLeftPanelVisible,
              clientSize = clientSize,
              logLevel = logLevel,
              exampleName = exampleName
            )(using viewOwner)
          // A bit hacky: we need to keep track of the last right panel section selected,
          // otherwise there's a noticeable transition none => something when switching diagrams
          state.rightPanelActiveSection.signal.changes.distinct.foreach(lastRightPanelSection = _)(using viewOwner)
          // Similarly track the left panel visibility state between diagrams
          state.leftPanelVisible.signal.changes.distinct.foreach(lastLeftPanelVisible = _)(using viewOwner)
          DesktopBridge.attach(state)
          // The session tier answers from HERE (D7.2): a socket client's "what is
          // selected" has no answer anywhere else.
          SessionCommands.attach(state)

          TopLevel(state, router, Commands(state, routerCmds), exampleName.map(exampleBanner(_, state, routerCmds)))
            .amend(onUnmountCallback { _ =>
              // Both targets are process-global and used to outlive the view
              // that set them, so a file event or a session query after
              // navigation was answered by a viewer nobody was looking at.
              DesktopBridge.detach(state)
              SessionCommands.detach(state)
              viewOwner.killSubscriptions()
            })

        val app =
          div(
            child <-- router.currentRoute.map:
              case Route.Home =>
                ProjectsDirectoryView(graphviz, router, routerCmds, viewerSettings, setTheme)

              case Route.ProjectDetail(id, source) =>
                diagramView(id, source, exampleName = None)

              case Route.Example(slug) =>
                DotExamples.bySlug.get(slug) match
                  case Some((name, example)) =>
                    // The text is fetched HERE rather than at click time, so the
                    // route survives a reload and a pasted link: an example is a
                    // real address, not a transient side effect of a click.
                    div(
                      cls := "contents",
                      child <-- FetchStream
                        .get(example.path)
                        .map(text => diagramView(s"example-$slug", Some(text), exampleName = Some(name)))
                    )
                  case None =>
                    // A renamed or removed example. Say so and offer the way back,
                    // rather than redirecting mid-render (a route change inside the
                    // route signal's own propagation) or drawing an empty viewer
                    // that just looks broken.
                    unknownExample(slug, routerCmds)
          )

        render(document.querySelector("#app"), app)

  /** The strip that makes an ephemeral visit legible: what you are looking at,
    * that it is not yours, and the one click that changes that.
    */
  private def exampleBanner(name: String, state: ViewerState, routerCmds: RouterCommands) =
    div(
      cls := "example-banner",
      span(cls := "example-banner-title", "Example: ", b(name)),
      span(cls := "example-banner-note", "Not saved to your library"),
      Button(
        "Copy to my library",
        // The text as it stands, not the file: whatever the reader tried out
        // while poking at the example comes with them into their copy.
        onClick --> (_ => routerCmds.copyExampleToLibrary.execute(Some((name, state.sourceText.now()))))
      ).primary.tiny
    )

  private def unknownExample(slug: String, routerCmds: RouterCommands) =
    div(
      cls := "unknown-example",
      h2("No such example"),
      p("There is no example named ", code(slug), " any more."),
      Button(
        "Back to the library",
        onClick --> (_ => routerCmds.navigateHome.execute())
      ).primary.small
    )

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
