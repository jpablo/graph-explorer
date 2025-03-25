package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.Mods
import org.jpablo.graphexplorer.viewer.domUtils.dataTip

def Tooltip(text: String, mods: Mods*) =
  div(
    cls := "flex-none tooltip",
    dataTip := text,
    mods
  )

