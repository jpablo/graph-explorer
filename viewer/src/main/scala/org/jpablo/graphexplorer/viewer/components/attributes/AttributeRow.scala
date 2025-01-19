package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.{DotAttribute, DotAttributeEnum, DotAttributeSimple}
import org.jpablo.graphexplorer.viewer.widgets.InputType

enum AttributeType:
  case AttributeHeader(title:  String)
  case AttributeRow(
      attrId:          String,
      label:           String,
      placeholderText: String,
      inputType:       InputType,
      inputValue:      Option[Var[Option[AttrValue]]] = None,
      options:         Seq[(String, AttrValue)] = Seq.empty,
      default:         String = ""
  )

  def isHeader: Boolean =
    this match
      case AttributeHeader(_) => true
      case _ => false

import AttributeType.*

object AttributeType:
  def attributeRow(attr: DotAttribute[?], inputType: InputType, inputValue: Option[Var[Option[AttrValue]]] = None) =
    AttributeRow(
      attrId          = attr.attrId,
      label           = attr.label,
      placeholderText = attr.placeholderText,
      inputType       = inputType,
      inputValue      = inputValue,
      options         = attr.valuesWithLabel.map((l, v) => (l, AttrValue(v.toString))).toSeq,
      default         = attr.default.toString
    )

  def buildRow(
    attrs: DotAttribute[?] | (DotAttribute[?], InputType) | String,
    inputValue: Option[Var[Option[AttrValue]]] = None
  ): Seq[AttributeType] =
    attrs match
      case ""                                     => Seq.empty
      case s: String                              => Seq(AttributeHeader(s))
      case (attr: DotAttribute[?], it: InputType) => Seq(attributeRow(attr, it, inputValue))
      case attr: DotAttributeEnum[?]              => Seq(attributeRow(attr, InputType.select, inputValue))
      case attr: DotAttributeSimple[?]            => Seq(attributeRow(attr, InputType.text, inputValue))

  def buildRows(attrs: DotAttribute[?] | (DotAttribute[?], InputType) | String*): Seq[AttributeType] =
    attrs.flatMap(buildRow(_))
