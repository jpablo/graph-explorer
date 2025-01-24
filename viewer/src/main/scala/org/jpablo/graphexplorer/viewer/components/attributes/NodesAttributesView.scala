package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeType.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number}
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.FillStyle
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeType.buildRow

def NodesAttributesView(parent: String, attrsVar: Var[Map[String, AttrValue]], selection: Boolean) =
  AttributesView(
    id    = "node-attributes",
    title = s"Node Attributes ($parent)",
    attrs = attrsVar,
    buildRows(
      "Label",
      if selection then Label -> InputType.multiText else "",
      LabelLoc,
      "Text Format",
      FontColor -> color,
      FontName,
      FontSize -> number(),
      "Shape",
      Shape,
      Sides       -> number(),
      Regular     -> checkbox,
      Orientation -> number(),
      "Fill"
    ),
    buildRow(FillStyle, Some(fillStyleVar(attrsVar))),
    buildRows(
      FillColor -> color,
      "Border"
    ),
    buildRow(Style, Some(borderStyleVar(attrsVar))),
    buildRows(
      Color       -> color,
      PenWidth    -> number(),
      Peripheries -> number(),
      "Other",
      if selection then URL else ""
    )
  )

/** Creates a Var that handles the fill style attribute for nodes.
  * 
  * This manages the "filled" value within the "style" attribute to control node fill styling.
  * It maps between the UI's FillStyle enum (NoFill/ColorFill) and the DOT "filled" style value.
  *
  * @param attrsVar The Var containing the node's attributes map
  * @return A Var that represents the current fill style and handles updates
  */
private def fillStyleVar(attrsVar: Var[Map[String, AttrValue]]): Var[Option[AttrValue]] =
  val filled = "filled"
  val baseAttribute = Style

  def getCurrentValue(attrs: Map[String, AttrValue]): Option[AttrValue] =
    Some(
      AttrValue(
        attrs.get(baseAttribute.attrId)
          .map(_.toString)
          .filter(_.contains(filled))
          .map(_ => FillStyle.ColorFill.toString)
          .getOrElse(FillStyle.NoFill.toString)
      )
    )

  def updateStyles(attrs: Map[String, AttrValue], value: Option[AttrValue]): Map[String, AttrValue] =
    value.fold(attrs): v =>
      val currentStyles = attrs.get(baseAttribute.attrId).map(_.toString).map(parseStyles).getOrElse(Set.empty)
      val newStyles =
        if v.toString.contains(FillStyle.ColorFill.toString) then
          currentStyles + filled
        else
          currentStyles - filled

      if newStyles.isEmpty then
        attrs - baseAttribute.attrId
      else
        attrs + (baseAttribute.attrId -> AttrValue(newStyles.mkString(",")))

  attrsVar.zoomLazy(getCurrentValue)(updateStyles)
end fillStyleVar


/** Creates a Var that handles the border style attribute for nodes.
  * 
  * This manages the non-"filled" values within the "style" attribute to control node border styling.
  * It preserves any "filled" style value while allowing other styles like "dashed", "dotted", etc.
  * to be modified independently.
  *
  * @param attrsVar The Var containing the node's attributes map
  * @return A Var that represents the current border style and handles updates
  */
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
end borderStyleVar

private def parseStyles(style: String): Set[String] =
  style.split(",").map(_.trim).filterNot(_.isEmpty).toSet
