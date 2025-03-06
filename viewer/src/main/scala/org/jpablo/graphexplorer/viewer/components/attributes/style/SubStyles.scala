package org.jpablo.graphexplorer.viewer.components.attributes.style

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.models.AttributesUpdates

// parse the style attribute into a StyleSubAttributes
def buildSubAttributeVar(
    attrsVar:        Var[AttributesUpdates],
    defaultSubAttrs: Signal[StyleSubAttributes]
)(using owner: Owner): Var[StyleSubAttributes] =
  attrsVar
    .zoomLazy(StyleSubAttributes.from): (attrs, userSelection) =>
      val default = getSubAttrsNow(defaultSubAttrs)
      val dotStyle = (default ++ userSelection).toDotString
      if default.toDotString == dotStyle then
        // otherwise changes to the default style will be ignored
        attrs - NodeStyle.attrId
      else
        attrs + (NodeStyle.attrId -> AttrValue(dotStyle))


def getSubAttrsNow(s: Signal[StyleSubAttributes])(using owner: Owner): StyleSubAttributes =
  s.observe.now()


