package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*


/*
  Input and Button have the same type erasure:
    `ReactiveHtmlElement[Any]`

  This has two consequences:
    1. Extensions with same name have to be disambiguated with @targetName
    2. They have to be defined in the same file.
 */

extension (elem: Input)
  def smallInput: Input = elem.amend(cls := "form-control-sm")


//extension (btn: Button)
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

  inline def toTooltip(text: String, pos: String = "tooltip-bottom") =
    Tooltip(text, cls := pos, elem)
