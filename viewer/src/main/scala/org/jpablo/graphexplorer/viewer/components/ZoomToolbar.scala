package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*

def ZoomToolbar(commands: Commands) =

  div(
    idAttr := "zoom-toolbar",
    cls    := "floating-toolbar",
    // ---------- zoom ----------
    Button(span().dashIcon, onClick --> commands.zoomOut.action()).tiny.ghost,
    Button(commands.fit.title, onClick --> commands.fit.action()).tiny.ghost,
    Button(span().plusIcon, onClick --> commands.zoomIn.action()).tiny.ghost
  )
