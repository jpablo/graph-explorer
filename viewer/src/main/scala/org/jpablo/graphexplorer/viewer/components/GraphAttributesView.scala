package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.FormInput

def GraphAttributesView(state: ViewerState) =
  val attrs = state.graphElementAttributes
  val rankdirVar = attrs.zoomLazy(_.rankdir.getOrElse(""))((a, rankdir) => a.copy(rankdir = Some(rankdir)))
  val labelVar = attrs.zoomLazy(_.label.getOrElse(""))((a, label) => a.copy(label = Some(label)))
  val splinesVar = attrs.zoomLazy(_.splines.getOrElse(""))((a, splines) => a.copy(splines = Some(splines)))
  val bgcolorVar = attrs.zoomLazy(_.bgcolor.getOrElse(""))((a, bgcolor) => a.copy(bgcolor = Some(bgcolor)))
  val fontnameVar = attrs.zoomLazy(_.fontname.getOrElse(""))((a, fontname) => a.copy(fontname = Some(fontname)))
  val fontcolorVar = attrs.zoomLazy(_.fontcolor.getOrElse(""))((a, fontcolor) => a.copy(fontcolor = Some(fontcolor)))
  val overlapVar = attrs.zoomLazy(_.overlap.getOrElse(""))((a, overlap) => a.copy(overlap = Some(overlap)))

  div(
    idAttr := "graph-attributes",
    div(
      FormInput("Direction", "TB, LR, BT, RL", rankdirVar),
      FormInput("Label", "Enter label here", labelVar),
      FormInput("Splines", "line, spline, polyline, ortho", splinesVar),
      FormInput("Background Color", "Enter background color here", bgcolorVar),
      FormInput("Font Name", "Enter font name here", fontnameVar),
      FormInput("Font Color", "Enter font color here", fontcolorVar),
      FormInput("Overlap", "false, scale, compress", overlapVar)
    )
  )
