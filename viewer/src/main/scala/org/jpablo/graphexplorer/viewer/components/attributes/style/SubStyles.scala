package org.jpablo.graphexplorer.viewer.components.attributes.style

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.components.attributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.components.attributes.views.getFillAndBorderStyle
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.{BorderStyle, CornerStyle}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.models.Attributes

def buildSubAttributeVar(
    attrsVar:        Var[Attributes],
    defaultSubAttrs: Signal[StyleSubAttributes]
)(using owner: Owner): Var[Option[StyleSubAttributes]] =
  attrsVar
    .zoomLazy(getFillAndBorderStyle)((attrs, subAttrsO) =>
      subAttrsO match
        case None => attrs - NodeStyle.attrId
        case Some(subAttrs) =>
          val default = getSubAttrsNow(defaultSubAttrs)
          val dotStyle = subAttrs.toDotString
          if default.toDotString == dotStyle then
            // otherwise changes to the default style will be ignored
            attrs - NodeStyle.attrId
          else
            attrs + (NodeStyle.attrId -> AttrValue(dotStyle))
    )

def getSubAttrsNow(s: Signal[StyleSubAttributes])(using owner: Owner): StyleSubAttributes =
  s.observe.now()

class CommonSubAttributes(attrsVar: Var[Attributes], defaultSubAttrs: Signal[StyleSubAttributes])(using owner: Owner):

  val subAttributeVar = buildSubAttributeVar(attrsVar, defaultSubAttrs)

  val boldStyle = BooleanSubAttr(_.bold, modify(_)(_.bold), subAttributeVar, defaultSubAttrs, getSubAttrsNow)
  val fillStyle = BooleanSubAttr(_.fill, modify(_)(_.fill), subAttributeVar, defaultSubAttrs, getSubAttrsNow)
  val invisibleStyle =
    BooleanSubAttr(_.invisible, modify(_)(_.invisible), subAttributeVar, defaultSubAttrs, getSubAttrsNow)

  val borderStyle =
    EnumSubAttr(
      _.border,
      modify(_)(_.border),
      BorderStyle.valueOf,
      BorderStyle.default,
      subAttributeVar,
      defaultSubAttrs,
      getSubAttrsNow
    )
  val shapeModeStyle =
    EnumSubAttr(
      _.shapeMod,
      modify(_)(_.shapeMod),
      CornerStyle.valueOf,
      CornerStyle.default,
      subAttributeVar,
      defaultSubAttrs,
      getSubAttrsNow
    )

