package org.jpablo.graphexplorer.viewer

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.projects.ProjectsView
import org.jpablo.graphexplorer.router.projectIdFromLocation
import org.jpablo.graphexplorer.viewer.components.TopLevel
import org.jpablo.graphexplorer.viewer.state.{ProjectId, ViewerState}
import org.scalajs.dom
import org.scalajs.dom.document

object Viewer:

  def main(args: Array[String]): Unit =
    render(
      container = document.querySelector("#app"),
      rootNode = projectIdFromLocation() match
        case None            => ProjectsView()
        case Some(projectId) => TopLevel(ViewerState(projectId))
    )

//  private def setupErrorHandling()(using Owner): EventBus[String] =
//    val errors = new EventBus[String]
//    AirstreamError.registerUnhandledErrorCallback: ex =>
//      errors.emit(ex.getMessage)
//    windowEvents(_.onError).foreach: e =>
//      errors.emit(e.message)
//    errors
