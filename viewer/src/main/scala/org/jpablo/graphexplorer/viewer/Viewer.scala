package org.jpablo.graphexplorer.viewer

import buildinfo.BuildInfo
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.projects.ProjectsDirectoryView
import org.jpablo.graphexplorer.router
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands, TopLevel}
import org.jpablo.graphexplorer.viewer.state.{ProjectId, ViewerState}
import org.scalajs.dom.{document, window}
import scala.scalajs.js.Date

object Viewer:

  def main(args: Array[String]): Unit =
    val errors     = setupErrorHandling()
    val router     = Router()
    val routerCmds = RouterCommands(router)

    val app =
      div(
        child <-- router.currentRoute.map:
          case Route.Home =>
            ProjectsDirectoryView(router, routerCmds)

          case Route.ProjectDetail(id) =>
            val state = ViewerState(ProjectId(id), window.navigator.clipboard.writeText, errors)
            TopLevel(state, router, Commands(state, routerCmds))
      )

    render(document.querySelector("#app"), app)

  private def setupErrorHandling(): EventBus[String] =
    given Owner = unsafeWindowOwner
    val errors  = new EventBus[String]
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
