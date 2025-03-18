package org.jpablo.graphexplorer.viewer.components.attributes.rows

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.formats.dot.ast.attributes.Layout
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.models.{AttributeId, SelectionAttrValue}
import org.jpablo.graphexplorer.viewer.widgets.InputType

sealed trait AttributeRow

object AttributeRow:
  sealed trait InputRow:
    def getValidLayouts: Set[Layout] =
      this match
        case ia: InputAttribute            => ia.validLayouts
        case DependentAttributes(ia, _, _) => ia.validLayouts

  case class AttributeHeader(title: String) extends AttributeRow

  case class InputAttribute(
      attrId:       AttributeId,
      label:        String,
      placeholder:  String,
      inputType:    InputType,
      inputVar:     Var[SelectionAttrValue],
      options:      Seq[AttributeRow.RowOption] = Seq.empty,
      default:      Signal[String],
      validLayouts: Set[Layout] = Set.empty
  ) extends AttributeRow, InputRow

  case class DependentAttributes(
      attribute: InputAttribute,
      visible:   Signal[Boolean],
      dependent: InputAttribute
  ) extends AttributeRow, InputRow

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
