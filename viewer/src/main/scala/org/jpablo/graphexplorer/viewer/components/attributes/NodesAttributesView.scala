package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeType.buildRows
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number}
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.FillStyle
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeType.buildRow
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState

def NodesAttributesView(parent: String, state: ViewerState, attrsVar: Var[Attributes], selection: Boolean) =
  AttributesView(
    id    = "node-attributes",
    title = s"Node Attributes ($parent)",
    attrs = attrsVar,
    defaults = Some(state.visibleGraph.map(_.root.nodeAttrs)),
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
private def fillStyleVar(attrsVar: Var[Attributes]): Var[Option[AttrValue]] =
  val styleAttrId = Style.attrId

  // Style => FillStyle
  def getCurrentValue(attrs: Attributes): Option[AttrValue] =
    Some(
      AttrValue(
        attrs.get(styleAttrId)
          .map(_.toString)
          .filter(_.contains(Style.filled))
          .map(_ => FillStyle.ColorFill.toString)
          .getOrElse(FillStyle.NoFill.toString)
      )
    )

  // FillStyle => Style
  def updateStyles(attrs: Attributes, attrValue: Option[AttrValue]): Attributes =
    attrValue.fold(attrs): value =>
      val currentStyles = attrs.get(styleAttrId).map(_.toString).map(parseStyles).getOrElse(Set.empty)
      val newStyles =
        if value.toString.contains(FillStyle.ColorFill.toString) then
          currentStyles + Style.filled
        else
          currentStyles - Style.filled

      if newStyles.isEmpty then
        attrs - styleAttrId
      else
        attrs + (styleAttrId -> AttrValue(newStyles.mkString(",")))

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
private def borderStyleVar(attrsVar: Var[Attributes]): Var[Option[AttrValue]] =

  def getCurrentValue(attrs: Attributes): Option[AttrValue] =
    attrs.get(Style.attrId)
      .map(_.toString)
      .map: style =>
        val styles = parseStyles(style)
        val value = (styles - Style.filled).mkString(",")
        AttrValue(if value.isEmpty then Style.default.toString else value)

  def updateStyles(attrs: Attributes, value: Option[AttrValue]): Attributes =
    value.fold(attrs) { v =>
      val currentStyles = attrs.get(Style.attrId).map(_.toString).map(parseStyles).getOrElse(Set.empty)
      val newStyles =
        if v.toString.isEmpty then
          currentStyles.filter(_ == Style.filled)
        else
          parseStyles(v.toString) ++ currentStyles.filter(_ == Style.filled)
      if newStyles.isEmpty then attrs - Style.attrId
      else attrs + (Style.attrId -> AttrValue(newStyles.mkString(",")))
    }

  attrsVar.zoomLazy(getCurrentValue)(updateStyles)
end borderStyleVar

private def parseStyles(style: String): Set[String] =
  style.split(",").map(_.trim).filterNot(_.isEmpty).toSet
