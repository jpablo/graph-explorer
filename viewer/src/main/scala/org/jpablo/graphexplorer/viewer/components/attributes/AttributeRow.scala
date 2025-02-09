package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.{DotAttribute, DotAttributeEnum, DotAttributeSimple}
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.widgets.InputType

enum AttributeRow:
  case AttributeHeader(title: String)

  case InputAttribute(
      attrId:       String,
      label:        String,
      placeholder:  String,
      inputType:    InputType,
      inputValue:   Var[Option[AttrValue]],
      options:      Seq[(String, AttrValue)] = Seq.empty,
      defaultValue: Signal[String]
  )

import AttributeRow.*

class RowBuilder(
    elementAttributes: Var[Attributes],
    defaults:          Option[Signal[Attributes]] = None
):

  def buildRows(
      dotAttributes: DotAttribute[?]
        | String
        | AttributeRow
        | (DotAttribute[?], InputType)*
  ): Seq[AttributeRow] =
    dotAttributes.map:
      case s: String         => AttributeHeader(s)
      case row: AttributeRow => row

      case dotAttr: (DotAttribute[?] | (DotAttribute[?], InputType)) =>
        val (attr, inputType) =
          dotAttr match
            case attr: DotAttributeEnum[?]              => (attr, InputType.select)
            case attr: DotAttributeSimple[?]            => (attr, InputType.text)
            case (attr: DotAttribute[?], it: InputType) => (attr, it)

        inputRow(attr -> inputType, simpleInputVar(attr.attrId, elementAttributes))

  def inputRow(
      attr:       (DotAttribute[?], InputType),
      inputValue: Var[Option[AttrValue]]
  ): AttributeRow =
    attr match
      case (attr: DotAttribute[?], it: InputType) =>
        InputAttribute(
          attrId       = attr.attrId,
          label        = attr.label,
          placeholder  = attr.placeholderText,
          inputType    = it,
          inputValue   = inputValue,
          options      = attr.valuesWithLabel.map((l, v) => (l, AttrValue(v.toString))).toSeq,
          defaultValue = defaultValue(attr.attrId, attr.default.toString)
        )

  private def simpleInputVar(attrId: String, attributes: Var[Attributes]): Var[Option[AttrValue]] =
    attributes.zoomLazy(_.values.get(attrId))((attrs, value) =>
      value match
        case None    => attrs - attrId // Remove override, will fall back to root value
        case Some(v) => attrs + (attrId -> v) // Set override
    )

  // uses the global default if present, otherwise uses the (hardcoded) default value.
  private def defaultValue(attrId: String, default: String): Signal[String] =
    defaults
      .map(_.map(_.get(attrId).map(_.toString).getOrElse(default)))
      .getOrElse(Signal.fromValue(default))
