package org.jpablo.graphexplorer.viewer

import buildinfo.BuildInfo
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.projects.{ProjectStorage, ProjectsDirectoryView}
import org.jpablo.graphexplorer.router
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands, TopLevel}
import org.jpablo.graphexplorer.viewer.state.{ProjectId, RightPanelSection, ViewerState}
import org.scalajs.dom.{document, window}

import scala.scalajs.js.Date

object Viewer:

  def main(args: Array[String]): Unit =
    given Owner    = unsafeWindowOwner
    val errors     = setupErrorHandling()
    val router     = Router()
    val routerCmds = RouterCommands(router)

    var lastRightPanelSection = RightPanelSection.none
    var lastLeftPanelVisible  = true

    def setTheme(theme: String): Unit =
      dom.document.documentElement.setAttribute("data-theme", theme)

    val viewerSettings = ProjectStorage.loadViewerSettings()
    viewerSettings.now().currentTheme.foreach(setTheme)

    val app =
      div(
        child <-- router.currentRoute.map:
          case Route.Home =>
            ProjectsDirectoryView(router, routerCmds)

          case Route.ProjectDetail(id, source) =>
            val state =
              ViewerState(
                projectId = ProjectId(id),
                writeText = window.navigator.clipboard.writeText,
                setTheme = setTheme,
                errorBus = errors,
                initialSource = source,
                initialRightPanelSection = lastRightPanelSection,
                initialLeftPanelVisible = lastLeftPanelVisible
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

  def printBanner() =
    val banner =
      s"""Welcome to Graph Explorer!
------------------------------
version:      ${BuildInfo.version}
scalaVersion: ${BuildInfo.scalaVersion}
sbtVersion:   ${BuildInfo.sbtVersion}
builtAt:      ${new Date(BuildInfo.builtAtString).toUTCString}
    """
    dom.console.info(banner)
