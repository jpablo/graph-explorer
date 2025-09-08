package org.jpablo.graphexplorer.viewer

import buildinfo.BuildInfo
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.projects.{ProjectStorage, ProjectsDirectoryView}
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands, TopLevel}
import org.jpablo.graphexplorer.viewer.logging.Level
import org.jpablo.graphexplorer.viewer.state.{ProjectId, RightPanelSection, ViewerState}
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
    val routerCmds = RouterCommands(router)

    var lastRightPanelSection = RightPanelSection.none
    var lastLeftPanelVisible  = false

    def setTheme(theme: String): Unit =
      document.documentElement.setAttribute("data-theme", theme)

    val viewerSettings = ProjectStorage.loadViewerSettings()
    viewerSettings.now().currentTheme.foreach(setTheme)

    // Determine ClientSize based on viewport width
    val mediaQueryList = window.matchMedia("(max-width: 768px)")
    val clientSize     = if (mediaQueryList.matches) ClientSize.Small else ClientSize.Normal

    // Parse log level from query string
    val queryParams = new URLSearchParams(window.location.search)
    val logLevel = Option(queryParams.get("logLevel"))
      .map(Level.fromString)
      .getOrElse(Level.None)

    // If a share URL (?dot=...) is present, resolve it immediately:
    val sharedDot = ShareUrl.readDotParam()
    sharedDot.foreach: dot =>
      ProjectStorage.findProjectByExactSource(dot) match
        case Some(existingId) =>
          router.navigateTo(Route.ProjectDetail(existingId.value))
        case None =>
          // Create a new project initialized with the provided DOT
          val newId = ProjectStorage.createProjectDirectoryEntry("Untitled")
          router.navigateTo(Route.ProjectDetail(newId.value, Some(dot)))

    Graphviz.build().foreach: (graphviz: Graphviz) =>
      dom.console.log("Graphviz (viz.js) initialized:", graphviz)
      printBanner()
      // Start the app after Graphviz is initialized

      val app =
        div(
          child <-- router.currentRoute.map:
            case Route.Home =>
              ProjectsDirectoryView(graphviz, router, routerCmds)

            case Route.ProjectDetail(id, source) =>
              val state =
                ViewerState(
                  projectId = ProjectId(id),
                  graphviz = graphviz,
                  writeText = window.navigator.clipboard.writeText,
                  setTheme = setTheme,
                  errorBus = errors,
                  infoBus = infos,
                  initialSource = source,
                  initialRightPanelSection = lastRightPanelSection,
                  initialLeftPanelVisible = lastLeftPanelVisible,
                  clientSize = clientSize,
                  logLevel = logLevel
                )
              // A bit hacky: we need to keep track of the last right panel section selected,
              // otherwise there's a noticeable transition none => something when switching diagrams
              state.rightPanelActiveSection.signal.changes.distinct.foreach(lastRightPanelSection = _)
              // Similarly track the left panel visibility state between diagrams
              state.leftPanelVisible.signal.changes.distinct.foreach(lastLeftPanelVisible = _)

              TopLevel(state, router, Commands(state, routerCmds))
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
