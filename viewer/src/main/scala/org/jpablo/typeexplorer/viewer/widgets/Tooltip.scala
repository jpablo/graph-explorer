package org.jpablo.typeexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.jpablo.typeexplorer.viewer.domUtils.dataTip

def Tooltip(text: String, mods: Modifier[ReactiveHtmlElement.Base]*) =
  div(
    cls := "flex-none tooltip tooltip-bottom",
    dataTip := text,
    mods
  )

