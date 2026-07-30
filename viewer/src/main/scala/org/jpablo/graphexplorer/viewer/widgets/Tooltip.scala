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

/** Tooltip position/alignment tokens — the one spelling of `tooltip-*`.
  * `bottomEnd` (alignment, daisyUI 5.6) is for elements at the window's right
  * edge, where a centre-aligned bubble clips off-screen. */
object TooltipPos:
  val top: String       = "tooltip-top"
  val bottom: String    = "tooltip-bottom"
  val left: String      = "tooltip-left"
  val right: String     = "tooltip-right"
  val bottomEnd: String = "tooltip-bottom tooltip-end"
  // For controls at the window's LEFT edge, where a centre-aligned bubble clips.
  val bottomStart: String = "tooltip-bottom tooltip-start"

