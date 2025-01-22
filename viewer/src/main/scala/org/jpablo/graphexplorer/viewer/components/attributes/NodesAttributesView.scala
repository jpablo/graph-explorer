package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeType.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number}
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.FillStyle
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeType.buildRow

def NodesAttributesView(attrsVar: Var[Map[String, AttrValue]], selection: Boolean) =

  AttributesView(
    id    = "node-attributes",
    title = "Node Attributes",
    attrs = attrsVar,
    buildRows(
      "Label",
      if selection then Label -> InputType.multiText else "",
      LabelLoc,
      "Text Format",
      FontColor -> color,
      FontName,
      FontSize  -> number(),
      "Shape",
      Shape,
      Sides       -> number(),
      Regular     -> checkbox,
      Orientation -> number(),
      "Fill",
    ),
    buildRow(FillStyle, Some(fillStyleVar(attrsVar))),
    buildRows(
      FillColor -> color,
      "Border"
    ),
    buildRow(Style, Some(borderStyleVar(attrsVar))),
    buildRows(
      Color     -> color,
      PenWidth    -> number(),
      Peripheries -> number(),
      "Other",
      if selection then URL else ""
    )
  )

private def fillStyleVar(attrsVar: Var[Map[String, AttrValue]]): Var[Option[AttrValue]] =
  val filled = "filled"

  def getCurrentValue(attrs: Map[String, AttrValue]): Option[AttrValue] =
    attrs.get(Style.attrId)
      .map: style =>
        AttrValue(
          (if style.toString.contains(filled) then FillStyle.ColorFill else FillStyle.NoFill).toString
        )

  def updateStyles(attrs: Map[String, AttrValue], value: Option[AttrValue]): Map[String, AttrValue] =
    value.fold(attrs) { v =>
      val currentStyles = attrs.get(Style.attrId).map(_.toString).map(parseStyles).getOrElse(Set.empty)
      val newStyles = 
        if v.toString.contains(FillStyle.ColorFill.toString) then
          currentStyles + filled
          else 
            currentStyles - filled
      pprint.log((currentStyles, v, newStyles))
      val attrs2 = 
        if newStyles.isEmpty then 
          pprint.log("empty, removing style from attrs")
          attrs - Style.attrId
        else 
          pprint.log("not empty, adding style to attrs")
          attrs + (Style.attrId -> AttrValue(newStyles.mkString(",")))
      pprint.log(attrs2)
      attrs2
    }

  attrsVar.zoomLazy(getCurrentValue)(updateStyles)

// handle "style" attribute by ignoring any "filled" value present
private def borderStyleVar(attrsVar: Var[Map[String, AttrValue]]): Var[Option[AttrValue]] =
  val filled = "filled"

  def getCurrentValue(attrs: Map[String, AttrValue]): Option[AttrValue] =
    attrs.get(Style.attrId)
      .map(_.toString)
      .map: style =>
        val styles = parseStyles(style)
        val value = (styles - filled).mkString(",")
        AttrValue(if value.isEmpty then Style.default.toString else value)

  def updateStyles(attrs: Map[String, AttrValue], value: Option[AttrValue]): Map[String, AttrValue] =
    value.fold(attrs) { v =>
      val currentStyles = attrs.get(Style.attrId).map(_.toString).map(parseStyles).getOrElse(Set.empty)
      val newStyles = 
        if v.toString.isEmpty then
          currentStyles.filter(_ == filled)
        else
          parseStyles(v.toString) ++ currentStyles.filter(_ == filled)
      if newStyles.isEmpty then attrs - Style.attrId
      else attrs + (Style.attrId -> AttrValue(newStyles.mkString(",")))
    }

  attrsVar.zoomLazy(getCurrentValue)(updateStyles)

private def parseStyles(style: String): Set[String] =
  style.split(",").map(_.trim).filterNot(_.isEmpty).toSet
