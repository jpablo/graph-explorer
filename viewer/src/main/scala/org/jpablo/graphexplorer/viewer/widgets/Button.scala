package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement


def Button(mods: Modifier[ReactiveHtmlElement.Base]*): Button =
  button(cls := "btn", mods)

