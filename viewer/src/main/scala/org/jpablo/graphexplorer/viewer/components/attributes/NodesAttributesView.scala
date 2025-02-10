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

  val fillStyleVar = FillStyleVar(attrsVar, defaults)

  AttributesView(
    id       = "node-attributes",
    titleStr = s"Node Attributes ($parent)",
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
      builder.inputRow(FillStyle -> InputType.select, fillStyleVar.getVar, fillStyleVar.getDefault)
    )
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

///** Creates a Var that handles the fill style attribute for nodes.
//  *
//  * This manages the "filled" value within the "style" attribute to control node fill styling. It maps between the UI's
//  * FillStyle enum (NoFill/ColorFill) and the DOT "filled" style value.
//  *
//  * @param attrsVar
//  *   The Var containing the node's attributes map
//  * @return
//  *   A Var that represents the current fill style and handles updates
//  */
//private def fillStyleVar(
//    attrsVar:  Var[Attributes],
//    defaultsO: Option[Signal[Attributes]]
//): Var[Option[AttrValue]] =
//  val styleAttrId = Style.attrId
//
//  def getDefaults(): FillAndBorderStyle =
//    val defaults = defaultsO.map(_.observe(using OneTimeOwner(() => ())).now()).getOrElse(Attributes.empty)
//    getFillAndBorderStyle(defaults)
//
//  // Style => FillStyle
//  def getCurrentValue(attrs: Attributes): Option[AttrValue] =
//    getFillAndBorderStyle(attrs).fill.map(f => AttrValue(f.toString))
//
//  // FillStyle => Style
//  def updateStyles(attrs: Attributes, valueO: Option[AttrValue]): Attributes =
//    val fillStyle = valueO.fold(getDefaults().fill)(fill => Some(FillStyle.valueOf(fill.toString)))
//    val dotStyle = getFillAndBorderStyle(attrs).copy(fill = fillStyle).toDotString
//    if dotStyle.isBlank then
//      attrs - styleAttrId
//    else
//      attrs + (styleAttrId -> AttrValue(dotStyle))
//
//  attrsVar.zoomLazy(getCurrentValue)(updateStyles)
//end fillStyleVar

class FillStyleVar(
    attrsVar:  Var[Attributes],
    defaultsO: Option[Signal[Attributes]]
):
  private val styleAttrId = Style.attrId

  private def getFillStyleDefaults: FillAndBorderStyle =
    val defaults = defaultsO.map(_.observe(using OneTimeOwner(() => ())).now()).getOrElse(Attributes.empty)
    getFillAndBorderStyle(defaults)

  // Style => FillStyle
  private def getCurrentValue(attrs: Attributes): Option[AttrValue] =
    pprint.log(getFillAndBorderStyle(attrs))
    getFillAndBorderStyle(attrs).fillStyle.map(f => AttrValue(f.toString))

  // FillStyle => Style
  private def updateStyles(attrs: Attributes, valueO: Option[AttrValue]): Attributes =
    dom.console.log(s"attrs: $attrs, valueO: $valueO")
    val defaultFillStyle = getFillStyleDefaults.fillStyle
    pprint.log(defaultFillStyle)
    val fillStyleO = valueO.fold(defaultFillStyle)(fill => Some(FillStyle.valueOf(fill.toString)))
    pprint.log(fillStyleO)
    val dotStyle = getFillAndBorderStyle(attrs).copy(fillStyle = fillStyleO).toDotString
    pprint.log(dotStyle)
    // FillStyle.ColorFill is represented as style="filled" in the style attribute
    // FillStyle.NoFill is represented as style="" in the style attribute
    
    // Rules:
    // - global no style, local no style => default local: NoFill
    // - global no style, local no style, user selects ColorFill => local style="filled"
    // - global no style, local style="filled", user selects NoFill => local no style (removed)
    // - global no style, local style="filled", user clicks reset => local no style (removed)

    // - global style="filled", local no style => default local: ColorFill
    // - global style="filled", local no style, user selects NoFill => local style=""
    // - global style="filled", local style="" => default local: NoFill
    // - global style="filled", local style="", user selects ColorFill  => local no style (removed)
    // - global style="filled", local style="", user clicks reset  => local no style (removed)

    if dotStyle.isBlank && !defaultFillStyle.contains(FillStyle.ColorFill) then
      attrs - styleAttrId
    else if defaultFillStyle == fillStyleO then
      attrs - styleAttrId
    else
      attrs + (styleAttrId -> AttrValue(dotStyle))

  // uses the global default if present, otherwise uses the (hardcoded) default value.
  val getDefault: Signal[String] =
    defaultsO
      .map(_.map(getFillAndBorderStyle).map(_.fillStyle.getOrElse(FillStyle.default)))
      .getOrElse(Signal.fromValue(FillStyle.default))
      .map(_.toString)

  val getVar =
    attrsVar.zoomLazy(getCurrentValue)(updateStyles)

end FillStyleVar

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
    attrsVar: Var[Attributes],
    defaults: Option[Signal[Attributes]]
): Var[Option[AttrValue]] =
  val styleAttrId = Style.attrId

  def getGlobalAttr(): FillAndBorderStyle =
    val globalAttrs = defaults.map(_.observe(using OneTimeOwner(() => ())).now()).getOrElse(Attributes.empty)
    getFillAndBorderStyle(globalAttrs)

  // Style => BorderStyle
  def getCurrentValue(attrs: Attributes): Option[AttrValue] =
    getFillAndBorderStyle(attrs).borderStyle.map(f => AttrValue(f.toString))

  // BorderStyle => Style
  def updateStyles(attrs: Attributes, valueOpt: Option[AttrValue]): Attributes =
    val globalAttr = getGlobalAttr()
    val nodeAttr = getFillAndBorderStyle(attrs)
    // -- fill style --
    val newBorderStyle = valueOpt.fold(globalAttr.borderStyle)(attrValue => Some(Style.valueOf(attrValue.toString)))
    val attrValue = nodeAttr.copy(borderStyle = newBorderStyle).toDotString
    if attrValue.isBlank then
      attrs - styleAttrId
    else
      attrs + (styleAttrId -> AttrValue(attrValue))

  attrsVar.zoomLazy(getCurrentValue)(updateStyles)
end borderStyleVar

case class FillAndBorderStyle(
    fillStyle:   Option[FillStyle],
    borderStyle: Option[Style]
):
  def toDotString: String =
    val fillPart = if fillStyle.contains(FillStyle.ColorFill) then Some(Style.filled) else None
    (fillPart.toSeq ++ borderStyle.toSeq).mkString(",")

object FillAndBorderStyle:
  val empty = FillAndBorderStyle(None, None)

  def from(attrValue: Option[AttrValue]): FillAndBorderStyle =
    attrValue match
      case None => FillAndBorderStyle.empty
      case Some(attrValue) =>
        val parts = attrValue.toString.split(",").map(_.trim).filterNot(_.isEmpty).toSet
        val (fillPart, borderPart) = parts.partition(_ == Style.filled.toString)
        val fillStyle =
          if fillPart.isEmpty then
            Some(FillStyle.NoFill)
          else
            fillPart.headOption.map(_ => FillStyle.ColorFill)
        FillAndBorderStyle(
          fillStyle = fillStyle,
          borderStyle = borderPart.headOption.map(Style.valueOf)
        )
