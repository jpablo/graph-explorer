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
extension (btn: HtmlElement)
  def asBtn = btn.amend(cls := "btn")
  def outline = btn.amend(cls := "btn-outline")
  def neutral = btn.amend(cls := "btn-neutral")
  def primary = btn.amend(cls := "btn-primary")
  def secondary = btn.amend(cls := "btn-secondary")
  def success = btn.amend(cls := "btn-success")
  def small = btn.amend(cls := "btn-sm")
  def tiny = btn.amend(cls := "btn-xs")
  def circle = btn.amend(cls := "btn-circle")
  def ghost = btn.amend(cls := "btn-ghost")
