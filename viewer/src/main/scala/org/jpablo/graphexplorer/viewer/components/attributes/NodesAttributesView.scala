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
  val filledStyle = "filled"
  attrsVar
  .zoomLazy(attrs => 
    attrs
    .get(Style.attrId)
    .map(_.toString.contains(filledStyle))
    .map: filled =>
      if filled then
        AttrValue(FillStyle.ColorFill.toString)
      else
        AttrValue(FillStyle.NoFill.toString)

  ): (attrs, value) => 
    value.fold(attrs): v =>
      val currentStyle = attrs.get(Style.attrId).map(_.toString).getOrElse("")
      val newStyle = 
        if v.toString.contains(FillStyle.ColorFill.toString) then
          if currentStyle.isEmpty then filledStyle
          else if !currentStyle.contains(filledStyle) then s"$currentStyle,$filledStyle"
          else currentStyle
        else
          if currentStyle.isEmpty then ""
          else
            currentStyle
              .split(",")
              .filterNot(_.trim == filledStyle)
              .mkString(",")
      
      if newStyle.isEmpty then attrs - Style.attrId
      else attrs + (Style.attrId -> AttrValue(newStyle))


private def borderStyleVar(attrsVar: Var[Map[String, AttrValue]]): Var[Option[AttrValue]] =
  attrsVar
  .zoomLazy(attrs => 
    attrs
    .get(Style.attrId)
    .map(_.toString)
    .map: style =>
      // Remove "filled" and clean up any empty/extra commas
      val borderStyle = style
        .split(",")
        .map(_.trim)
        .filterNot(_ == "filled")
        .mkString
      
      AttrValue(borderStyle)
  ): (attrs, value) => 
    value.fold(attrs): newBorderStyle =>
      val currentStyle = attrs.get(Style.attrId).map(_.toString).getOrElse("")
      val hasFilled = currentStyle.split(",").map(_.trim).contains("filled")
      
      val newStyle = 
        if newBorderStyle.toString.isEmpty then
          if hasFilled then "filled"
          else ""
        else if hasFilled then
          s"${newBorderStyle.toString},filled"
        else
          newBorderStyle.toString
      
      if newStyle.isEmpty then attrs - Style.attrId
      else attrs + (Style.attrId -> AttrValue(newStyle))
