package org.jpablo.typeexplorer.viewer.widgets

import com.raquo.laminar.api.L
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.scalajs.dom.HTMLElement

object Join:
  opaque type Join <: Div = Div

  def apply(mods: ReactiveHtmlElement[HTMLElement]*): Join =
    div(
      cls := "join",
      mods.map(_.amend(cls := "join-item"))
    )
