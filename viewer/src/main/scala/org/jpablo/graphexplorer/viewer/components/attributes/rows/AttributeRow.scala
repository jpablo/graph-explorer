package org.jpablo.graphexplorer.viewer.components.attributes.rows

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.models.{AttributeId, SelectionAttrValue}
import org.jpablo.graphexplorer.viewer.widgets.InputType

enum AttributeRow:
  case AttributeHeader(title: String)

  case InputAttribute(
      attrId:      AttributeId,
      label:       String,
      placeholder: String,
      inputType:   InputType,
      inputVar:    Var[SelectionAttrValue],
      options:     Seq[AttributeRow.RowOption] = Seq.empty,
      default:     Signal[String]
  )

object AttributeRow:

  def _withDefault(row: InputAttribute) = row.inputVar.signal.combineWith(row.default)

  extension (row: InputAttribute)

    def isChanged: Signal[Boolean] =
      _withDefault(row).map { (attr, d) => attr.exists(_.toString != d) }

    def withDefault =
      _withDefault(row)

    def withDefaultString: Signal[String] =
      row.withDefault.map((v, d) => v.getOrElse(d).toString)

    def withDefaultBoolean: Signal[Boolean] =
      row.withDefaultString.map(_ == true.toString)

  case class RowOption(
      name:    String,
      value:   SelectionAttrValue,
      preview: Option[() => ReactiveSvgElement[dom.SVGSVGElement]] = None
  ):
    def hasValue(s: String) =
      value.exists(_.toString == s)
