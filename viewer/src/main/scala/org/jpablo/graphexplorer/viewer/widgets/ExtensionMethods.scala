package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*


extension (elem: HtmlElement)
  def asBtn = elem.amend(cls := "btn")
  def outline = elem.amend(cls := "btn-outline")
  def neutral = elem.amend(cls := "btn-neutral")
  def primary = elem.amend(cls := "btn-primary")
  def secondary = elem.amend(cls := "btn-secondary")
  def success = elem.amend(cls := "btn-success")
  def small = elem.amend(cls := "btn-sm")
  def tiny = elem.amend(cls := "btn-xs")
  def circle = elem.amend(cls := "btn-circle")
  def ghost = elem.amend(cls := "btn-ghost")
  def soft = elem.amend(cls := "btn-soft")

  /** Pressed-state that follows a signal (daisyUI `btn-active`). */
  def activeWhen(flag: Signal[Boolean]) = elem.amend(cls("btn-active") <-- flag)

  inline def toTooltip(text: String, pos: String = "tooltip-bottom") =
    Tooltip(text, cls := pos, elem)
