package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.*

def Drawer(
    id:        String,
    drawerEnd: Boolean = false,
    content:   Div => Div = identity,
    sidebar:   Div => Div = identity
) =
  val drawerOpen = Var(true)
  div(
    cls := "drawer",
    cls("lg:drawer-open") <-- drawerOpen,
    cls("drawer-end") := drawerEnd,
    input(
      cls := "drawer-toggle", idAttr := id, tpe := "checkbox",
      onClick.mapToValue --> (_ => {println("clicked"); drawerOpen.toggle() })
    ),
    content(div(cls("drawer-content"))),
    sidebar(
      div(
        cls := "drawer-side",
        label(forId := id, cls := "drawer-overlay")
      )
    )
  )
