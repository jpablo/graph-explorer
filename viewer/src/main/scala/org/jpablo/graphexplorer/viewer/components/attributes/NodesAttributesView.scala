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
  styleVar(attrsVar, "filled", _.contains(FillStyle.ColorFill.toString))

private def borderStyleVar(attrsVar: Var[Map[String, AttrValue]]): Var[Option[AttrValue]] =
  styleVar(attrsVar, "filled", _.nonEmpty, keepStyle = true)

private def styleVar(
    attrsVar: Var[Map[String, AttrValue]], 
    styleValue: String,
    shouldIncludeStyle: String => Boolean,
    keepStyle: Boolean = false
): Var[Option[AttrValue]] =
  
  def getCurrentValue(attrs: Map[String, AttrValue]): Option[AttrValue] =
    attrs.get(Style.attrId)
      .map(_.toString)
      .map: style =>
        val styles = parseStyles(style)
        if !keepStyle then
          AttrValue(if styles.contains(styleValue) then FillStyle.ColorFill.toString else FillStyle.NoFill.toString)
        else
          AttrValue(styles.filterNot(_ == styleValue).mkString(","))
    

  def updateStyles(attrs: Map[String, AttrValue], value: Option[AttrValue]): Map[String, AttrValue] =
    value.fold(attrs) { v =>
      val currentStyles = attrs.get(Style.attrId).map(_.toString).map(parseStyles).getOrElse(Set.empty)
      
      val newStyles = 
        if keepStyle then
          if v.toString.isEmpty then
            currentStyles.filter(_ == styleValue)
          else
            parseStyles(v.toString) ++ currentStyles.filter(_ == styleValue)
        else
          if shouldIncludeStyle(v.toString) then
            currentStyles + styleValue 
          else 
            currentStyles - styleValue
          
      if newStyles.isEmpty then attrs - Style.attrId
      else attrs + (Style.attrId -> AttrValue(newStyles.mkString(",")))
    }

  attrsVar.zoomLazy(getCurrentValue)(updateStyles)

private def parseStyles(style: String): Set[String] =
  style.split(",").map(_.trim).filterNot(_.isEmpty).toSet
