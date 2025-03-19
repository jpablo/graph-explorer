package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*

object Join:
  opaque type Join <: Div = Div

  def apply(mods: HtmlElement*): Join =
    div(
      cls := "join",
      mods.map(_.amend(cls := "join-item"))
    )
