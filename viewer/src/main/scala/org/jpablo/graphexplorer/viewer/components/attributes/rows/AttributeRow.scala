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

  def _combineDefault(row: InputAttribute): Signal[(SelectionAttrValue, String)] = 
    row.inputVar.signal.combineWith(row.default)

  extension (row: InputAttribute)

    def combineDefault = _combineDefault(row)

    def isChanged: Signal[Boolean] =
      row.combineDefault.map { (attr, d) => attr.exists(_.toString != d) }

    def combineDefaultString: Signal[String] =
      row.combineDefault.map((v, d) => v.getOrElse(d).toString)

    def combineDefaultBoolean: Signal[Boolean] =
      row.combineDefaultString.map(_ == true.toString)

  case class RowOption(
      name:    String,
      value:   SelectionAttrValue,
      preview: Option[() => ReactiveSvgElement[dom.SVGSVGElement]] = None
  ):
    def hasValue(s: String) =
      value.exists(_.toString == s)
