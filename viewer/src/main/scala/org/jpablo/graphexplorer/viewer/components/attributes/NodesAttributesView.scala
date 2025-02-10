package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number}
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
  val borderStyleVar = BorderStyleVar(attrsVar, defaults)

  AttributesView(
    id       = "node-attributes",
    titleStr = s"Node Attributes ($parent)",
    builder.buildRows(
      "Label",
      if selection then Label -> InputType.multiText else "",
      LabelLoc,
      if selection then XLabel else "",
//      Xlp -> number(),
      "Text Format",
      FontColor -> color,
      FontName,
      FontSize -> number(),
      "Shape",
      Shape,
      Sides       -> number(),
      Regular     -> checkbox,
      Orientation -> number(),
      "Fill",
      builder.inputRow(FillStyle -> InputType.select, fillStyleVar.getVar, fillStyleVar.getDefault),
      FillColor -> color,
      "Border",
      builder.inputRow(Style -> InputType.select, borderStyleVar.getVar, borderStyleVar.getDefault),
      Color       -> color,
      PenWidth    -> number(),
      Peripheries -> number(),
      "Other",
      if selection then URL else ""
    )
  )

private def getFillAndBorderStyle(attrs: Attributes) =
  FillAndBorderStyle.from(attrs.get(Style.attrId))

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
    getFillAndBorderStyle(attrs).fillStyle.map(f => AttrValue(f.toString))

  // FillStyle => Style
  private def updateStyles(attrs: Attributes, valueO: Option[AttrValue]): Attributes =
    val defaultFillStyle = getFillStyleDefaults
    val fillStyle = valueO.map(fill => FillStyle.valueOf(fill.toString))
    val dotStyle = (defaultFillStyle ++ getFillAndBorderStyle(attrs).copy(fillStyle = fillStyle)).toDotString

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

    if dotStyle.isBlank && !defaultFillStyle.fillStyle.contains(FillStyle.ColorFill) then
      attrs - styleAttrId
    else if dotStyle == defaultFillStyle.toDotString then
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

class BorderStyleVar(
    attrsVar: Var[Attributes],
    defaults: Option[Signal[Attributes]]
):
  val styleAttrId = Style.attrId

  private def getFillStyleDefaults: FillAndBorderStyle =
    val globalAttrs = defaults.map(_.observe(using OneTimeOwner(() => ())).now()).getOrElse(Attributes.empty)
    getFillAndBorderStyle(globalAttrs)

  // Style => BorderStyle
  private def getCurrentValue(attrs: Attributes): Option[AttrValue] =
    getFillAndBorderStyle(attrs).borderStyle.map(f => AttrValue(f.toString))

  // BorderStyle => Style
  private def updateStyles(attrs: Attributes, valueOpt: Option[AttrValue]): Attributes =
    val defaultBorderStyle = getFillStyleDefaults
    val borderStyleO = valueOpt.map(attrValue => Style.valueOf(attrValue.toString))
    val dotStyle = (defaultBorderStyle ++ getFillAndBorderStyle(attrs).copy(borderStyle = borderStyleO)).toDotString
    // Rules:
    // - global no style, local no style => default local: solid
    // - global no style, local no style, user selects dashed => local style="dashed"
    // - global no style, local style="dashed", user selects solid => local no style (removed)  FIXME
    // - global no style, local style="dashed", user clicks reset => local no style (removed)

    // - global style="dashed", local no style => default local: dashed
    // - global style="dashed", local no style, user selects solid => local style="solid"
    // - global style="dashed", local style="solid" => default local: solid
    // - global style="dashed", local style="solid", user selects dashed  => local no style (removed)
    // - global style="dashed", local style="solid", user clicks reset  => local no style (removed)
    if dotStyle.isBlank then
      attrs - styleAttrId
    else if dotStyle == defaultBorderStyle.toDotString then
      attrs - styleAttrId
    else
      attrs + (styleAttrId -> AttrValue(dotStyle))

  // uses the global default if present, otherwise uses the (hardcoded) default value.
  val getDefault: Signal[String] =
    defaults
      .map(_.map(getFillAndBorderStyle).map(_.borderStyle.getOrElse(Style.default)))
      .getOrElse(Signal.fromValue(Style.default))
      .map(_.toString)

  val getVar =
    attrsVar.zoomLazy(getCurrentValue)(updateStyles)
end BorderStyleVar

case class FillAndBorderStyle(
    fillStyle:   Option[FillStyle],
    borderStyle: Option[Style]
):
  def toDotString: String =
    val fillPart = if fillStyle.contains(FillStyle.ColorFill) then Some(Style.filled) else None
    (fillPart.toSeq ++ borderStyle.toSeq).mkString(",")

  def ++(other: FillAndBorderStyle): FillAndBorderStyle =
    FillAndBorderStyle(
      fillStyle   = other.fillStyle.orElse(fillStyle),
      borderStyle = other.borderStyle.orElse(borderStyle)
    )

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
          fillStyle   = fillStyle,
          borderStyle = borderPart.headOption.map(Style.valueOf)
        )
