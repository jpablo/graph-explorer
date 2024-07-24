package org.jpablo.typeexplorer.viewer

import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.components.TopLevel
import org.jpablo.typeexplorer.viewer.state.ViewerState
import org.scalajs.dom

object Viewer:

  def main(args: Array[String]): Unit =
    given Owner = unsafeWindowOwner
    val appElem = createApp()
    render(dom.document.querySelector("#app"), appElem)

  private def createApp()(using Owner) =
    val state = ViewerState()
    TopLevel(state)


//  private def setupErrorHandling()(using Owner): EventBus[String] =
//    val errors = new EventBus[String]
//    AirstreamError.registerUnhandledErrorCallback: ex =>
//      errors.emit(ex.getMessage)
//    windowEvents(_.onError).foreach: e =>
//      errors.emit(e.message)
//    errors
