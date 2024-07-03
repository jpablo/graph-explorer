package org.jpablo.typeexplorer.viewer

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.typeexplorer.viewer.components.TopLevel
import org.jpablo.typeexplorer.viewer.examples.Example1
import org.jpablo.typeexplorer.viewer.state.ViewerState
import org.scalajs.dom

object Viewer:

  def main(args: Array[String]): Unit =
    val appElem = createApp()
    render(dom.document.querySelector("#app"), appElem)

  private def createApp() =
    given Owner = unsafeWindowOwner
    val renderDot = (new Graphviz).renderDot
    val state = ViewerState(Example1.graph.toCSV.asString(), renderDot)
    TopLevel(state)

//  private def setupErrorHandling()(using Owner): EventBus[String] =
//    val errors = new EventBus[String]
//    AirstreamError.registerUnhandledErrorCallback: ex =>
//      errors.emit(ex.getMessage)
//    windowEvents(_.onError).foreach: e =>
//      errors.emit(e.message)
//    errors
