package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
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
import com.raquo.airstream.ownership.OneTimeOwner

def NodesAttributesView(parent: String, state: ViewerState, attrsVar: Var[Attributes], selection: Boolean) =
  val defaults = state.visibleGraph.map(_.root.nodeAttrs)
  AttributesView(
    id    = "node-attributes",
    titleStr = s"Node Attributes ($parent)",
    attrs = attrsVar,
    defaults = Some(defaults),
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
    buildRow(FillStyle, Some(fillStyleVar(attrsVar, defaults))),
    buildRows(FillColor -> color, "Border"),
    buildRow(Style, Some(borderStyleVar(attrsVar, defaults))),
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
private def fillStyleVar(attrsVar: Var[Attributes], defaults: Signal[Attributes]): Var[Option[AttrValue]] =
  val styleAttrId = Style.attrId

  // Style => FillStyle
  def getCurrentValue(attrs: Attributes): Option[AttrValue] =
    attrs.get(styleAttrId)
      .map(FillAndBorderStyle.from)
      .map(_.fill.getOrElse(FillStyle.NoFill).toString)
      .map(AttrValue(_))

  // FillStyle => Style
  def updateStyles(attrs: Attributes, valueOpt: Option[AttrValue]): Attributes =
    val currentDefaults = defaults.observe(using OneTimeOwner(() => ())).now()
    val existingStyle = attrs.get(styleAttrId).map(FillAndBorderStyle.from).getOrElse(FillAndBorderStyle.empty)
    val currentDefaultStyle = currentDefaults.get(styleAttrId).map(FillAndBorderStyle.from).getOrElse(FillAndBorderStyle.empty)
    val newFillStyle = 
      valueOpt.fold(currentDefaultStyle.fill)(attrValue => Some(FillStyle.valueOf(attrValue.toString)))

    val newStyle = existingStyle.copy(fill = newFillStyle)
    attrs + (styleAttrId -> AttrValue(newStyle.toDotString))


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
private def borderStyleVar(attrsVar: Var[Attributes], defaults: Signal[Attributes]): Var[Option[AttrValue]] =
  val styleAttrId = Style.attrId

  // Style => BorderStyle
  def getCurrentValue(attrs: Attributes): Option[AttrValue] =
    attrs.get(styleAttrId)
      .map(FillAndBorderStyle.from)
      .map(_.border.getOrElse(Style.default).toString)
      .map(AttrValue(_))

  // BorderStyle => Style
  def updateStyles(attrs: Attributes, valueOpt: Option[AttrValue]): Attributes =
    val currentDefaults = defaults.observe(using OneTimeOwner(() => ())).now()
    val existingStyle = attrs.get(styleAttrId).map(FillAndBorderStyle.from).getOrElse(FillAndBorderStyle.empty)
    val currentDefaultStyle = currentDefaults.get(styleAttrId).map(FillAndBorderStyle.from).getOrElse(FillAndBorderStyle.empty)
    val newBorder = 
      valueOpt.fold(currentDefaultStyle.border)(attrValue => Some(Style.valueOf(attrValue.toString)))

    val newStyle = existingStyle.copy(border = newBorder)
    attrs + (styleAttrId -> AttrValue(newStyle.toDotString))


  attrsVar.zoomLazy(getCurrentValue)(updateStyles)
end borderStyleVar

private def parseStyles(style: String): Set[String] =
  style.split(",").map(_.trim).filterNot(_.isEmpty).toSet


case class FillAndBorderStyle(
  fill: Option[FillStyle], 
  border: Option[Style]
):
  def toDotString: String =
    val fillPart = if fill.contains(FillStyle.ColorFill) then Some(Style.filled) else None
    (fillPart.toSeq ++ border.toSeq).mkString(",")

object FillAndBorderStyle:
  val empty = FillAndBorderStyle(None, None)
  
  def from(attrValue: AttrValue): FillAndBorderStyle =
    val parts = attrValue.toString.split(",").map(_.trim).filterNot(_.isEmpty).toSet
    val (fillPart, borderPart) = parts.partition(_ == Style.filled.toString)
    FillAndBorderStyle(
      fill = fillPart.headOption.map(_ => FillStyle.ColorFill),
      border = borderPart.headOption.map(Style.valueOf)
    )
