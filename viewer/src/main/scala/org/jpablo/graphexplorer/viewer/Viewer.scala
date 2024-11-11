package org.jpablo.graphexplorer.viewer

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.projects.ProjectsDirectoryView
import org.jpablo.graphexplorer.router
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.TopLevel
import org.jpablo.graphexplorer.viewer.state.{ProjectId, ViewerState}
import org.scalajs.dom
import org.scalajs.dom.document

object Viewer:

  def main(args: Array[String]): Unit =
    val router = Router()
    render(
      container = document.querySelector("#app"),
      rootNode = router.now() match
        case Route.Home                     => ProjectsDirectoryView(router)
        case Route.ProjectDetail(projectId) => TopLevel(ViewerState(ProjectId(projectId)), router)
    )

  private def setupErrorHandling()(using Owner): EventBus[String] =
    val errors = new EventBus[String]
    AirstreamError.registerUnhandledErrorCallback: ex =>
      errors.emit(ex.getMessage)
    windowEvents(_.onError).foreach: e =>
      errors.emit(e.message)
    errors
