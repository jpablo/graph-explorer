package org.jpablo.typeexplorer.viewer.widgets

import com.raquo.laminar.api.L.*

def Drawer(
    id:        String,
    drawerEnd: Boolean = false,
    content:   Div => Div = identity,
    sidebar:   Div => Div = identity
) =
  div(
    cls := "drawer",
    cls := (if drawerEnd then "drawer-end" else ""),
    input(cls := "drawer-toggle", idAttr := id, tpe := "checkbox"),
    content(div(cls := "drawer-content")),
    sidebar(
      div(
        cls := "drawer-side z-20 h-full absolute",
        label(forId := id, cls := "drawer-overlay")
      )
    )
  )
