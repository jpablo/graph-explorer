package org.jpablo.typeexplorer.viewer

import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.typeexplorer.viewer.components.TopLevel
import org.jpablo.typeexplorer.viewer.examples.Example1
import org.jpablo.typeexplorer.viewer.state.ViewerState
import org.scalajs.dom
import io.laminext.syntax.core.*
import com.github.plokhotnyuk.jsoniter_scala.core.*
import org.jpablo.typeexplorer.viewer.state.VisibleNodes.given

object Viewer:

  def main(args: Array[String]): Unit =
    val appElem = createApp()
    render(dom.document.querySelector("#app"), appElem)

  private def createApp() =
    val renderDot = (new Graphviz).renderDot
    val example = Example1.graph.toCSV.asString()
    val state = ViewerState("", renderDot)
    persist("viewer.state", state.storage)
    TopLevel(state)

  private def persist[A](name: String, signal: Signal[A])(using JsonValueCodec[A]) =
    given Owner = unsafeWindowOwner
    val ss = storedString(name, initial = "{}")
    signal.foreach: a =>
      ss.set(writeToString(a))

//  private def setupErrorHandling()(using Owner): EventBus[String] =
//    val errors = new EventBus[String]
//    AirstreamError.registerUnhandledErrorCallback: ex =>
//      errors.emit(ex.getMessage)
//    windowEvents(_.onError).foreach: e =>
//      errors.emit(e.message)
//    errors
