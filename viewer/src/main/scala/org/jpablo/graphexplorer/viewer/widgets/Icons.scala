package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import com.raquo.laminar.tags.HtmlTag
import org.scalajs.dom

object Icons:

  def chevron(
      $isOpen: Signal[Boolean],
      mods:    Modifier[Anchor]*
  ) =
    a(
      cls := "bi inline-block w-5",
      cls <-- $isOpen.map(o => if o then "bi-chevron-down" else "bi-chevron-right")
    ).amend(mods)

  extension (tag: HtmlTag[dom.HTMLElement])
    def barChartStepsIcon = tag(cls := "bi bi-bar-chart-steps")
    def boxesIcon = tag(cls := "bi bi-boxes")
    def closeIcon = tag(cls := "bi bi-x-circle")
    def dashIcon = tag(cls := "bi bi-dash")
    def fileBinaryIcon = tag(cls := "bi bi-file-binary")
    def fileCodeIcon = tag(cls := "bi bi-file-code")
    def folderIcon = tag(cls := "bi bi-folder")
    def folderMinusIcon = tag(cls := "bi bi-folder-minus")
    def folderPlusIcon = tag(cls := "bi bi-folder-plus")
    def listIcon = tag(cls := "bi bi-list")
    def layoutSidebarIcon = tag(cls := "bi bi-layout-sidebar")
    def plusCircleIcon = tag(cls := "bi bi-plus-circle")
    def plusIcon = tag(cls := "bi bi-plus")
