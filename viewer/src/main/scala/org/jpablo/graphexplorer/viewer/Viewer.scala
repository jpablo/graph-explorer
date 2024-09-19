package org.jpablo.graphexplorer.viewer

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.TopLevel
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.scalajs.dom

object Viewer:

  def main(args: Array[String]): Unit =
    render(
      container = dom.document.querySelector("#app"),
      rootNode = TopLevel(ViewerState())
    )


//  private def setupErrorHandling()(using Owner): EventBus[String] =
//    val errors = new EventBus[String]
//    AirstreamError.registerUnhandledErrorCallback: ex =>
//      errors.emit(ex.getMessage)
//    windowEvents(_.onError).foreach: e =>
//      errors.emit(e.message)
//    errors
