package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.{DotAttribute, DotAttributeEnum, DotAttributeSimple}
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.widgets.InputType

enum AttributeRow:
  case AttributeHeader(title: String)

  case InputAttribute(
      attrId:      String,
      label:       String,
      placeholder: String,
      inputType:   InputType,
      inputVar:    Var[Option[AttrValue]],
      options:     Seq[AttributeRow.RowOption] = Seq.empty,
      default:     Signal[String]
  )

object AttributeRow:
  case class RowOption(
      name:    String,
      value:   AttrValue,
      preview: Option[() => ReactiveSvgElement[dom.SVGSVGElement]] = None
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
    dotAttributes
      .filter:
        case "" => false
        case _  => true
      .map:
        case s: String         => AttributeHeader(s)
        case row: AttributeRow => row

        case dotAttr: (DotAttribute[?] | (DotAttribute[?], InputType)) =>
          val (attr, inputType) =
            dotAttr match
              case attr: DotAttributeEnum[?]              => (attr, InputType.select)
              case attr: DotAttributeSimple[?]            => (attr, InputType.text)
              case (attr: DotAttribute[?], it: InputType) => (attr, it)
          simpleRow(attr, inputType)

  def simpleRow(
      attr:        DotAttribute[?],
      inputType:   InputType,
      onReset:     Option[String] = None,
      label:       Option[String] = None,
      placeholder: Option[String] = None
  ) =
    inputRow(
      attr        = attr -> inputType,
      inputVar    = simpleInputVar(attr.attrId, elementAttributes, onReset),
      default     = defaultValue(attr.attrId, attr.default.toString),
      label       = label,
      placeholder = placeholder
    )

  def inputRow(
      attr:        (DotAttribute[?], InputType),
      inputVar:    Var[Option[AttrValue]],
      default:     Signal[String],
      label:       Option[String] = None,
      placeholder: Option[String] = None
  ): InputAttribute =
    attr match
      case (attr: DotAttribute[?], it: InputType) =>
        InputAttribute(
          attrId      = attr.attrId,
          label       = label.getOrElse(attr.label),
          placeholder = placeholder.getOrElse(attr.placeholderText),
          inputType   = it,
          inputVar    = inputVar,
          options     = attr.valuesWithLabel.map((l, v) => RowOption(l, AttrValue(v.toString), None)).toSeq,
          default     = default
        )

  def simpleInputVar(
      attrId:     String,
      attributes: Var[Attributes],
      onReset:    Option[String] = None
  ): Var[Option[AttrValue]] =
    attributes.zoomLazy(_.values.get(attrId))((attrs, value) =>
      value match
        case None =>
          onReset match
            case Some(v) => attrs + (attrId -> AttrValue(v))
            case None    => attrs - attrId // Remove override, will fall back to root value
        case Some(attrValue) => attrs + (attrId -> attrValue)
    )

  // uses the global default if present, otherwise uses the (hardcoded) default value.
  def defaultValue(attrId: String, default: String): Signal[String] =
    defaults
      .map(_.map(_.get(attrId).map(_.toString).getOrElse(default)))
      .getOrElse(Signal.fromValue(default))
