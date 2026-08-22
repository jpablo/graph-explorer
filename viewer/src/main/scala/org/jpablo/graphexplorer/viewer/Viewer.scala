package org.jpablo.graphexplorer.viewer

import buildinfo.BuildInfo
import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.projects.{DesktopLibrary, DesktopMigration, Library, ProjectsDirectoryView}
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.backends.graphviz.{DotExamples, Graphviz}
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands, TopLevel, resolveTheme}
import org.jpablo.graphexplorer.viewer.desktop.{
  DesktopBridge,
  DesktopDocumentRegistry,
  DesktopIpc,
  DesktopOpenRequests,
  SessionCommands
}
import org.jpablo.graphexplorer.viewer.logging.Level
import org.jpablo.graphexplorer.viewer.state.{
  DocumentSessionId,
  PersistedDiagramState,
  ProjectId,
  RightPanelSection,
  ViewerState,
  ViewTarget
}
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

      // Installed at the WINDOW level, not per view: an open request routinely
      // arrives while the app is on Home, which is precisely when there is no
      // viewer to deliver it to.
      //
      // And installed HERE rather than at the top of main, because installing
      // announces `viewer_ready` — the signal the shell waits on before
      // delivering an open. Announcing it before `Library.install` had run
      // would let an early open be answered "no such diagram" by a library that
      // had simply not loaded yet.
      DesktopOpenRequests.install(router.navigateTo, id => Library.projectExists(ProjectId(id)))

      Graphviz.build().foreach: (graphviz: Graphviz) =>
        dom.console.log("Graphviz initialized (Scala port for dot, viz-js for other engines):", graphviz)
        printBanner()
        // Start the app after Graphviz is initialized

        /** The diagram page. `exampleName` is what makes it a read-only visit: it
          * turns off persistence and puts the "copy to my library" strip on top.
          * Both routes share this so an example is the SAME viewer, not a
          * second, weaker one.
          */
        def diagramView(target: ViewTarget, source: Option[String]) =
          val exampleName = target match
            case ViewTarget.Example(_, name) => Some(name)
            case _                           => None
          // Owner scoped to this project visit: killed when the view unmounts, so the
          // ViewerState's subscriptions (phases, persistence, theme, panels...) — and the
          // whole object graph they retain — are released instead of leaking one full
          // ViewerState per navigation on the never-killed window owner.
          val viewOwner = new ManualOwner
          val state =
            ViewerState(
              target = target,
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
              logLevel = logLevel
            )(using viewOwner)
          // A bit hacky: we need to keep track of the last right panel section selected,
          // otherwise there's a noticeable transition none => something when switching diagrams
          state.rightPanelActiveSection.signal.changes.distinct.foreach(lastRightPanelSection = _)(using viewOwner)
          // Similarly track the left panel visibility state between diagrams
          state.leftPanelVisible.signal.changes.distinct.foreach(lastLeftPanelVisible = _)(using viewOwner)
          DesktopBridge.attach(state)

          // §7.4: a loose file with an unsaved edit asks before it is left.
          // Registered per view and cleared on unmount below, so exactly one
          // guard exists and it belongs to what is on screen.
          val leaveGuard: Route => Boolean = route =>
            if state.documentIsDirty then
              state.pendingLeave.set(Some(route))
              false
            else true
          router.guardNavigation(leaveGuard)

          // The window closing is the case a dialog cannot serve: the browser
          // owns that prompt and allows only its own wording. §7.4 rules out
          // doing the work in `pagehide` instead — IPC during teardown is not
          // guaranteed to finish, so a save started there may never land.
          val warnOnClose: js.Function1[dom.Event, Unit] = event =>
            if state.documentIsDirty then
              event.preventDefault()
              event.asInstanceOf[js.Dynamic].updateDynamic("returnValue")("")
          dom.window.addEventListener("beforeunload", warnOnClose)
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
              router.clearNavigationGuard(leaveGuard)
              dom.window.removeEventListener("beforeunload", warnOnClose)
              state.closePersistence()
              viewOwner.killSubscriptions()
            })

        /** A loose file: the same viewer, and a different store (§5, §6).
          *
          * The SAME `diagramView`, on purpose. A loose file is not a weaker
          * diagram, and a second, thinner viewer would drift from this one.
          * Only the target differs, and the target is what chooses the store.
          *
          * The text is not passed as `source`. It comes from
          * `LooseFilePersistence.initial`, which reads the registry — so one
          * place holds what the shell sent, and a reload of this route shows
          * the file again rather than an empty diagram.
          */
        def looseDocumentView(sessionId: String) =
          DocumentSessionId.parse(sessionId).filter(DesktopDocumentRegistry.get(_).isDefined) match
            case Some(session) =>
              diagramView(ViewTarget.LooseFile(session), source = None)
            case None =>
              // A bookmark can outlive the session it names, and a person can
              // type this URL. Say so, and offer the way back. Opening an empty
              // viewer here would offer a save with no file to write to.
              unknownDocumentSession(routerCmds)

        val app =
          div(
            child <-- router.currentRoute.map:
              case Route.Home =>
                ProjectsDirectoryView(graphviz, router, routerCmds, viewerSettings, setTheme)

              case Route.ProjectDetail(id, source) =>
                diagramView(ViewTarget.library(id), source)

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
                        .map(text => diagramView(ViewTarget.Example(slug, name), Some(text)))
                    )
                  case None =>
                    // A renamed or removed example. Say so and offer the way back,
                    // rather than redirecting mid-render (a route change inside the
                    // route signal's own propagation) or drawing an empty viewer
                    // that just looks broken.
                    unknownExample(slug, routerCmds)

              case Route.LooseDocument(sessionId) =>
                looseDocumentView(sessionId)
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

  /** A `/documents/<id>` route whose session the page does not hold. */
  private def unknownDocumentSession(routerCmds: RouterCommands) =
    div(
      cls := "loose-document-placeholder",
      h2("No such document session"),
      p("This page shows a file the desktop opened. That session is not open now."),
      Button(
        "Back to the library",
        onClick --> (_ => routerCmds.navigateHome.execute())
      ).primary.small
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
