package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.components.attributes.AttributeRow.RowOption
import org.jpablo.graphexplorer.viewer.extensions.extraAttributes.{
  BoldStyle,
  BorderStyle,
  CornerStyle,
  FillStyle,
  InvisibleStyle
}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.*
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.InputType
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, color, number, range}

def NodesAttributesView(
    parent:    String,
    state:     ViewerState,
    attrsVar:  Var[Attributes],
    defaults:  Option[Signal[Attributes]] = None,
    selection: Boolean
) =
  val builder = RowBuilder(attrsVar, defaults)

  val isSingleNodeSelected = state.diagramSelection.signal.map(_.size == 1)
  val labelRow =
    if selection then
      isSingleNodeSelected.map(single =>
        if single then builder.simpleRow(Label, InputType.multiText, onReset = Some("")) else ""
      ).observe(using state.owner).now()
    else
      ""

  val defaultSubAttrs: Signal[StyleSubAttributes] =
    defaults
      .map(_.map(attrs => getFillAndBorderStyle(attrs).getOrElse(StyleSubAttributes.empty)))
      .getOrElse(Signal.fromValue(StyleSubAttributes.empty))

  def getSubAttrsNow(s: Signal[StyleSubAttributes]): StyleSubAttributes =
    s.observe(using state.owner).now()

  val subAttributeVar: Var[Option[StyleSubAttributes]] =
    attrsVar
      .zoomLazy(getFillAndBorderStyle)((attrs, subAttrsO) =>
        subAttrsO match
          case None => attrs - NodeStyle.attrId
          case Some(subAttrs) =>
            val default = getSubAttrsNow(defaultSubAttrs)

//            val combined = default ++ subAttrs
//            pprint.log((default, subAttrs, combined))

            val dotStyle = subAttrs.toDotString
            if default.toDotString == dotStyle then
              // otherwise changes to the default style will be ignored
              attrs - NodeStyle.attrId
            else
              attrs + (NodeStyle.attrId -> AttrValue(dotStyle))
      )

//  val subAttributeFilledVar: Var[StyleSubAttributes] =
//    attrsVar
//      .zoomLazy(attrs => getFillAndBorderStyle(attrs))((attrs, subAttrs) =>
//        val default = defaultSubAttrsNow()
//        val combined = default ++ subAttrs
//        val dotStyle = combined.toDotString
//        pprint.log(attrs)
//        pprint.log(subAttrs)
//        pprint.log(default)
//        pprint.log(combined)
//        pprint.log(dotStyle)
//        if default.toDotString == dotStyle then
//          attrs - NodeStyle.attrId
//        else
//          attrs + (NodeStyle.attrId -> AttrValue(dotStyle))
//      )

  // -------------------
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
      defaultSubAttrs
    )
  val shapeModeStyle =
    EnumSubAttr(
      _.shapeMod,
      modify(_)(_.shapeMod),
      CornerStyle.valueOf,
      CornerStyle.default,
      subAttributeVar,
      defaultSubAttrs
    )

  val borderStyleRow =
    builder
      .inputRow(BorderStyle -> InputType.selectWithPreview, borderStyle.getVar, borderStyle.getDefault)
      .copy(
        options =
          BorderStyle.valuesWithLabel.toSeq.map: (label, style) =>
            RowOption(label, AttrValue(style.toString), BorderStylePreview(style))
      )

  val shapeModeStyleRow =
    builder
      .inputRow(CornerStyle -> InputType.select, shapeModeStyle.getVar, shapeModeStyle.getDefault)

  val shapeRow: AttributeRow =
    builder
      .simpleRow(Shape, InputType.selectWithPreviewGrid)
      .copy(
        options =
          Shape.valuesWithLabel.filterNot((l, s) => Shape.synonyms.contains(s)).toSeq.map: (label, style) =>
            RowOption(label, AttrValue(style.toString), ShapePreview(style, 30, 20))
      )

  AttributesView(
    id       = "node-attributes",
    titleStr = s"Node Attributes ($parent)",
    builder.buildRows(
      "Label",
      labelRow,
      LabelLoc,
      if selection then XLabel else "",
      "Text Format",
      FontColor -> color,
      FontName,
      FontSize -> number(start = Some(1), end = Some(100), step = Some(1)),
      "Shape",
      shapeRow,
      Sides       -> number(start = Some(3), end = Some(10), step = Some(1)),
      Regular     -> checkbox,
      Orientation -> range(start = Some(0), end = Some(360), step = Some(1)),
      Peripheries -> number(start = Some(1), end = Some(10), step = Some(1)),
      "Style",
      builder.inputRow(FillStyle -> InputType.checkbox, fillStyle.getVar, fillStyle.getDefault),
      FillColor -> color,
      borderStyleRow,
      PenWidth -> range(start = Some(0.0), end = Some(10.0), step = Some(0.1)),
      Color    -> color,
      builder.inputRow(BoldStyle -> InputType.checkbox, boldStyle.getVar, boldStyle.getDefault),
      shapeModeStyleRow
    ),
    if selection then
      builder.buildRows(
        builder.inputRow(InvisibleStyle -> InputType.checkbox, invisibleStyle.getVar, invisibleStyle.getDefault),
        "Other",
        URL
      )
    else Seq.empty
  )

private def getFillAndBorderStyle(attrs: Attributes) =
  attrs.get(NodeStyle.attrId).map(StyleSubAttributes.from)

class BooleanSubAttr(
    getSubAttr:       StyleSubAttributes => Boolean,
    pathModify:       StyleSubAttributes => PathModify[StyleSubAttributes, Boolean],
    subAttributeVar:  Var[Option[StyleSubAttributes]],
    defaultSubAttrsS: Signal[StyleSubAttributes],
    getSubAttrsNow:   Signal[StyleSubAttributes] => StyleSubAttributes
):
  val getVar: Var[Option[AttrValue]] =
    subAttributeVar.zoomLazy(subAttrs =>
      subAttrs.map(getSubAttr).map(b => AttrValue(b.toString))
    )((subAttrs, attrValueO) =>
      (subAttrs, attrValueO) match

        case (None, None) => None

        case (None, Some(attrValue)) =>
          val defaultSubAttrs = getSubAttrsNow(defaultSubAttrsS)
          Some(pathModify(defaultSubAttrs).setTo(attrValue.isTrue))

        case (Some(subAttrs), None) =>
          val defaultSubAttrs = getSubAttrsNow(defaultSubAttrsS)
          Some(pathModify(subAttrs).setTo(getSubAttr(defaultSubAttrs)))

        case (Some(subAttrs), Some(attrValue)) =>
          Some(pathModify(subAttrs).setTo(attrValue.isTrue))
    )

  val getDefault: Signal[String] =
    defaultSubAttrsS.map(getSubAttr).map(_.toString)
end BooleanSubAttr

class EnumSubAttr[A](
    getSubAttr:      StyleSubAttributes => A,
    pathModify:      StyleSubAttributes => PathModify[StyleSubAttributes, A],
    valueOf:         String => A,
    hardDefault:     A,
    subAttributeVar: Var[Option[StyleSubAttributes]],
    defaultSubAttrs: Signal[StyleSubAttributes]
):
  val getVar: Var[Option[AttrValue]] =
    subAttributeVar.zoomLazy(subAttrs =>
      subAttrs.map(getSubAttr).map(b => AttrValue(b.toString))
    )((subAttrs, attrValueO) =>
      pprint.log((subAttrs, attrValueO))

      (subAttrs, attrValueO) match
        case (None, None) => None

        case (None, Some(attrValue)) =>
          val value = valueOf(attrValue.toString)
          Some(pathModify(StyleSubAttributes.empty).setTo(value))
//          if value == hardDefault then
//            // missing style but the attribute value is the default so no need to add it
//            None
//          else
//            // we need to go from a missing style to a style with the sub-attribute set to the new value
//            Some(pathModify(StyleSubAttributes.empty).setTo(value))

        case (Some(subAttrs), None) =>
          // Not sure if this is correct: the user intention is to remove the modification
          // but if we return None, the whole style will be removed, not just the sub-attribute
          // It seems like the correct behavior is to set the sub-attribute to the same value as the default?
          Some(pathModify(subAttrs).setTo(hardDefault))

        case (Some(subAttrs), Some(attrValue)) =>
          Some(pathModify(subAttrs).setTo(valueOf(attrValue.toString)))
    )

  val getDefault: Signal[String] =
    defaultSubAttrs.map(getSubAttr).map(_.toString)
end EnumSubAttr

//class FillStyleVar(
//    attrsVar:  Var[Attributes],
//    defaultsO: Option[Signal[Attributes]]
//):
//  private val styleAttrId = NodeStyle.attrId
//
//  private def getFillStyleDefaults: StyleSubAttributes =
//    val defaults = defaultsO.map(_.observe(using OneTimeOwner(() => ())).now()).getOrElse(Attributes.empty)
//    getFillAndBorderStyle(defaults)
//
//  // Style => FillStyle
//  private def getCurrentValue(attrs: Attributes): Option[AttrValue] =
//    getFillAndBorderStyle(attrs).fill.map(f => AttrValue(f.toString))
//
//  // FillStyle => Style
//  private def updateStyles(attrs: Attributes, valueO: Option[AttrValue]): Attributes =
//    val defaultFillStyle = getFillStyleDefaults
//    val fillStyle = valueO.map(fill => FillStyle.valueOf(fill.toString))
//    val dotStyle = (defaultFillStyle ++ getFillAndBorderStyle(attrs).copy(fill = fillStyle)).toDotString
//
//    // FillStyle.ColorFill is represented as style="filled" in the style attribute
//    // FillStyle.NoFill is represented as style="" in the style attribute
//
//    // Rules:
//    // - global no style, local no style => default local: NoFill
//    // - global no style, local no style, user selects ColorFill => local style="filled"
//    // - global no style, local style="filled", user selects NoFill => local no style (removed)
//    // - global no style, local style="filled", user clicks reset => local no style (removed)
//
//    // - global style="filled", local no style => default local: ColorFill
//    // - global style="filled", local no style, user selects NoFill => local style=""
//    // - global style="filled", local style="" => default local: NoFill
//    // - global style="filled", local style="", user selects ColorFill  => local no style (removed)
//    // - global style="filled", local style="", user clicks reset  => local no style (removed)
//
//    if dotStyle.isBlank && !defaultFillStyle.fill.contains(FillStyle.ColorFill) then
//      attrs - styleAttrId
//    else if dotStyle == defaultFillStyle.toDotString then
//      attrs - styleAttrId
//    else
//      attrs + (styleAttrId -> AttrValue(dotStyle))
//
//  // uses the global default if present, otherwise uses the (hardcoded) default value.
//  val getDefault: Signal[String] =
//    defaultsO
//      .map(_.map(getFillAndBorderStyle).map(_.fill.getOrElse(FillStyle.default)))
//      .getOrElse(Signal.fromValue(FillStyle.default))
//      .map(_.toString)
//
//  val getVar: Var[Option[AttrValue]] =
//    attrsVar.zoomLazy(getCurrentValue)(updateStyles)
//
//end FillStyleVar

//class BorderStyleVar(
//    attrsVar: Var[Attributes],
//    defaults: Option[Signal[Attributes]]
//):
//  val styleAttrId = NodeStyle.attrId
//
//  private def getFillStyleDefaults: StyleSubAttributes =
//    val globalAttrs = defaults.map(_.observe(using OneTimeOwner(() => ())).now()).getOrElse(Attributes.empty)
//    getFillAndBorderStyle(globalAttrs)
//
//  // Style => BorderStyle
//  private def getCurrentValue(attrs: Attributes): Option[AttrValue] =
//    getFillAndBorderStyle(attrs).border.map(f => AttrValue(f.toString))
//
//  // BorderStyle => Style
//  private def updateStyles(attrs: Attributes, valueOpt: Option[AttrValue]): Attributes =
//    val defaultBorderStyle = getFillStyleDefaults
//    val borderStyleO = valueOpt.map(attrValue => NodeStyle.valueOf(attrValue.toString))
//    val dotStyle = (defaultBorderStyle ++ getFillAndBorderStyle(attrs).copy(border = borderStyleO)).toDotString
//    // Rules:
//    // - global no style, local no style => default local: solid
//    // - global no style, local no style, user selects dashed => local style="dashed"
//    // - global no style, local style="dashed", user selects solid => local no style (removed)  FIXME
//    // - global no style, local style="dashed", user clicks reset => local no style (removed)
//
//    // - global style="dashed", local no style => default local: dashed
//    // - global style="dashed", local no style, user selects solid => local style="solid"
//    // - global style="dashed", local style="solid" => default local: solid
//    // - global style="dashed", local style="solid", user selects dashed  => local no style (removed)
//    // - global style="dashed", local style="solid", user clicks reset  => local no style (removed)
//    if dotStyle.isBlank then
//      attrs - styleAttrId
//    else if dotStyle == defaultBorderStyle.toDotString then
//      attrs - styleAttrId
//    else
//      attrs + (styleAttrId -> AttrValue(dotStyle))
//
//  // uses the global default if present, otherwise uses the (hardcoded) default value.
//  val getDefault: Signal[String] =
//    defaults
//      .map(_.map(getFillAndBorderStyle).map(_.border.getOrElse(NodeStyle.default)))
//      .getOrElse(Signal.fromValue(NodeStyle.default))
//      .map(_.toString)
//
//  val getVar =
//    attrsVar.zoomLazy(getCurrentValue)(updateStyles)
//end BorderStyleVar
