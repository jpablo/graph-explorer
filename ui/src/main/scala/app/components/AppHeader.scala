package app.components

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import bootstrap.{dropdown, navbar}

def appHeader(selectionBus: EventBus[String]) =
  navbar (
    id    = "te-header",
    brand = "Type Explorer",
    dropdown (
      label = "Diagram",
      elements = List("Inheritance", "Call graph"),
      selectionBus = selectionBus
    )
  )
