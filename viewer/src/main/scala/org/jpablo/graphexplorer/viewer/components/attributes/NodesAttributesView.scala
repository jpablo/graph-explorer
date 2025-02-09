package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
// import org.jpablo.graphexplorer.viewer.widgets.InputType
// import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number}
// import org.jpablo.graphexplorer.viewer.widgets.InputType.color
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.FillStyle
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import com.raquo.airstream.ownership.OneTimeOwner

def NodesAttributesView(
    parent:    String,
    state:     ViewerState,
    attrsVar:  Var[Attributes],
    defaults:  Option[Signal[Attributes]] = None,
    selection: Boolean
) =
  val builder = RowBuilder(attrsVar)

  AttributesView(
    id       = "node-attributes",
    titleStr = s"Node Attributes ($parent)",
    attrs    = attrsVar,
    defaults = defaults,
    builder.buildRows(
      //   "Label",
      //   if selection then Label -> InputType.multiText else "",
      //   LabelLoc,
      //   "Text Format",
      //   FontColor -> color,
      //   FontName,
      //   FontSize -> number(),
      //   "Shape",
      //   Shape,
      //   Sides       -> number(),
      //   Regular     -> checkbox,
      //   Orientation -> number(),
      //   "Fill"
    ),
    Seq(builder.buildRow(FillStyle -> InputType.select, fillStyleVar(attrsVar, defaults)))
    // buildRows(
    //   FillColor -> color,
    //   "Border"
    // ),
    // buildRow(Style, Some(borderStyleVar(attrsVar, defaults))),
    // buildRows(
    //   Color       -> color,
    // PenWidth    -> number(),
    // Peripheries -> number(),
    // "Other",
    // if selection then URL else ""
    // )
  )

private def getFillAndBorderStyle(attrs: Attributes) =
  FillAndBorderStyle.from(attrs.get(Style.attrId))

/** Creates a Var that handles the fill style attribute for nodes.
  *
  * This manages the "filled" value within the "style" attribute to control node fill styling. It maps between the UI's
  * FillStyle enum (NoFill/ColorFill) and the DOT "filled" style value.
  *
  * @param attrsVar
  *   The Var containing the node's attributes map
  * @return
  *   A Var that represents the current fill style and handles updates
  */
private def fillStyleVar(
    attrsVar:        Var[Attributes],
    globalNodeAttrs: Option[Signal[Attributes]]
): Var[Option[AttrValue]] =
  val styleAttrId = Style.attrId

  def getGlobalAttr(): FillAndBorderStyle =
    val globalAttrs = globalNodeAttrs.map(_.observe(using OneTimeOwner(() => ())).now()).getOrElse(Attributes.empty)
    getFillAndBorderStyle(globalAttrs)

  // Style => FillStyle
  def getCurrentValue(attrs: Attributes): Option[AttrValue] =
    Some(
      AttrValue(
        getFillAndBorderStyle(attrs)
          .fill
          .orElse(getGlobalAttr().fill)
          .getOrElse(FillStyle.default)
          .toString
      )
    )

  // FillStyle => Style
  def updateStyles(attrs: Attributes, valueOpt: Option[AttrValue]): Attributes =
    val globalAttr = getGlobalAttr()
    val nodeAttr = getFillAndBorderStyle(attrs)
    // -- fill style --
    val newFillStyle = valueOpt.fold(globalAttr.fill)(attrValue => Some(FillStyle.valueOf(attrValue.toString)))
    val attrValue = nodeAttr.copy(fill = newFillStyle).toDotString
    if attrValue.isBlank then
      attrs - styleAttrId
    else
      attrs + (styleAttrId -> AttrValue(attrValue))

  attrsVar.zoomLazy(getCurrentValue)(updateStyles)
end fillStyleVar

/** Creates a Var that handles the border style attribute for nodes.
  *
  * This manages the non-"filled" values within the "style" attribute to control node border styling. It preserves any
  * "filled" style value while allowing other styles like "dashed", "dotted", etc. to be modified independently.
  *
  * @param attrsVar
  *   The Var containing the node's attributes map
  * @return
  *   A Var that represents the current border style and handles updates
  */
private def borderStyleVar(
    attrsVar:        Var[Attributes],
    globalNodeAttrs: Option[Signal[Attributes]]
): Var[Option[AttrValue]] =
  val styleAttrId = Style.attrId

  def getGlobalAttr(): FillAndBorderStyle =
    val globalAttrs = globalNodeAttrs.map(_.observe(using OneTimeOwner(() => ())).now()).getOrElse(Attributes.empty)
    getFillAndBorderStyle(globalAttrs)

  // Style => BorderStyle
  def getCurrentValue(attrs: Attributes): Option[AttrValue] =
    Some(
      AttrValue(
        getFillAndBorderStyle(attrs)
          .border
          .orElse(getGlobalAttr().border)
          .getOrElse(FillStyle.default)
          .toString
      )
    )

  // BorderStyle => Style
  def updateStyles(attrs: Attributes, valueOpt: Option[AttrValue]): Attributes =
    val globalAttr = getGlobalAttr()
    val nodeAttr = getFillAndBorderStyle(attrs)
    // -- fill style --
    val newBorderStyle = valueOpt.fold(globalAttr.border)(attrValue => Some(Style.valueOf(attrValue.toString)))
    val attrValue = nodeAttr.copy(border = newBorderStyle).toDotString
    if attrValue.isBlank then
      attrs - styleAttrId
    else
      attrs + (styleAttrId -> AttrValue(attrValue))

  attrsVar.zoomLazy(getCurrentValue)(updateStyles)
end borderStyleVar

private def parseStyles(style: String): Set[String] =
  style.split(",").map(_.trim).filterNot(_.isEmpty).toSet

case class FillAndBorderStyle(
    fill:   Option[FillStyle],
    border: Option[Style]
):
  def toDotString: String =
    val fillPart = if fill.contains(FillStyle.ColorFill) then Some(Style.filled) else None
    (fillPart.toSeq ++ border.toSeq).mkString(",")

object FillAndBorderStyle:
  val empty = FillAndBorderStyle(None, None)

  def from(attrValue: Option[AttrValue]): FillAndBorderStyle =
    attrValue match
      case None => FillAndBorderStyle.empty
      case Some(attrValue) =>
        val parts = attrValue.toString.split(",").map(_.trim).filterNot(_.isEmpty).toSet
        val (fillPart, borderPart) = parts.partition(_ == Style.filled.toString)
        FillAndBorderStyle(
          fill   = fillPart.headOption.map(_ => FillStyle.ColorFill),
          border = borderPart.headOption.map(Style.valueOf)
        )
