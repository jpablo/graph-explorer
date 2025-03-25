package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.Mods


def Button(mods: Mods*): Button =
  button(cls := "btn", mods)

