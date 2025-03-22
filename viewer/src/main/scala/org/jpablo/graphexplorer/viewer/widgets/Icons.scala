package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*

object Icons:

  def chevron(
      $isOpen: Signal[Boolean],
      mods:    Modifier[Anchor]*
  ) =
    a(
      cls := "bi inline-block w-5",
      cls <-- $isOpen.map(o => if o then "bi-chevron-down" else "bi-chevron-right")
    ).amend(mods)

  extension (elem: HtmlElement)
    def barChartStepsIcon = elem.amend(cls := "bi bi-bar-chart-steps")
    def circleIcon = elem.amend(cls := "bi bi-circle")
    def diamondIcon = elem.amend(cls := "bi bi-diamond")
    def biSquareIcon = elem.amend(cls := "bi bi-square")
    def boxSeamIcon = elem.amend(cls := "bi bi-box-seam")
    def boxesIcon = elem.amend(cls := "bi bi-boxes")
    def chevronDownIcon = elem.amend(cls := "bi bi-chevron-down")
    def chevronLeftIcon = elem.amend(cls := "bi bi-chevron-left")
    def chevronRightIcon = elem.amend(cls := "bi bi-chevron-right")
    def closeIcon = elem.amend(cls := "bi bi-x-circle")
    def dashIcon = elem.amend(cls := "bi bi-dash")
    def fileBinaryIcon = elem.amend(cls := "bi bi-file-binary")
    def fileCodeIcon = elem.amend(cls := "bi bi-file-code")
    def folderIcon = elem.amend(cls := "bi bi-folder")
    def folderMinusIcon = elem.amend(cls := "bi bi-folder-minus")
    def folderPlusIcon = elem.amend(cls := "bi bi-folder-plus")
    def houseIcon = elem.amend(cls := "bi bi-house")
    def layoutSidebarIcon = elem.amend(cls := "bi bi-layout-sidebar")
    def layoutSidebarReverseIcon = elem.amend(cls := "bi bi-layout-sidebar-reverse")
    def listIcon = elem.amend(cls := "bi bi-list")
    def pencilSquareIcon = elem.amend(cls := "bi bi-pencil-square")
    def plusCircleIcon = elem.amend(cls := "bi bi-plus-circle")
    def plusIcon = elem.amend(cls := "bi bi-plus")
    def threeDotsVertical = elem.amend(cls := "bi bi-three-dots-vertical")
