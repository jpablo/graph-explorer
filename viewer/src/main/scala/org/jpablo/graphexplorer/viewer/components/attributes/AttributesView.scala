package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.widgets.{Checked, InputType, InputWithValue, SelectWithValue, TextAreaWithValue}
import AttributeType.*

def AttributesView(
    id:    String,
    title: String,
    attrs: Var[Map[String, AttrValue]],
    rows:  Seq[AttributeType]*
) =
  // TODO: Finish implementing this
  //  def getFonts(): js.Dynamic = js.Dynamic.global.window.queryLocalFonts().`then`(x => dom.console.log(x))
  div(
    idAttr := id,
    cls    := "attributes-view",
    table(
      cls := "table mt-3",
      buildGroups(rows.flatten).map:
        case Left(headers) =>
          thead(for h <- headers yield tr(th(colSpan := 2, h.title)))

        case Right(attrRows) =>
          tbody(
            for row <- attrRows yield
              tr(
                td(row.label),
                td(buildInputCell(title, row, attrs)),
              )
          )
    )
  )



private def buildGroups(rows:  Seq[AttributeType]) =
  var rr: List[Either[List[AttributeHeader], List[AttributeRow]]] = List.empty

  for rowType <- rows do
    (rr, rowType) match
      case (Nil, h: AttributeHeader)            => rr = List(Left(List(h)))
      case (Left(hs) :: t, h: AttributeHeader)  => rr = Left(h :: hs) :: t
      case (Right(rs) :: t, h: AttributeHeader) => rr = Left(List(h)) :: Right(rs) :: t

      case (Nil, r: AttributeRow)               => rr = List(Right(List(r)))
      case (Left(hs) :: t, r: AttributeRow)     => rr = Right(List(r)) :: Left(hs) :: t
      case (Right(rs) :: t, r: AttributeRow)    => rr = Right(r :: rs) :: t

  rr.map:
    case Left(hs) => Left(hs.reverse)
    case Right(rs) => Right(rs.reverse)
  .reverse



private def buildInputCell(parent: String, row: AttributeRow, attrsVar: Var[Map[String, AttrValue]]) =
  // zoom into the attribute with name row.attrId, but preserve the root value if specific value is removed
  lazy val inputVarStr: Var[Option[AttrValue]] =
    attrsVar.zoomLazy(
      _.get(row.attrId)
    )((attrs, value) => 
      value match {
        case None => attrs - row.attrId  // Remove override, will fall back to root value
        case Some(v) => attrs + (row.attrId -> v)  // Set override
      }
    )

  row.inputType match
    case InputType.select =>
      SelectWithValue(parent, row.attrId, row.options, row.inputValue.getOrElse(inputVarStr), row.default)

    case InputType.checkbox =>
      val inputVarBool = inputVarStr.zoomLazy(_.map(_.toString.contains(true.toString)))((_, b) => b.map(v => AttrValue(v.toString)))
      Checked(row.placeholderText, inputVarBool, row.default == true.toString)

    case InputType.`multiText` =>
      TextAreaWithValue(row.placeholderText, inputVarStr, row.default/*, setFocus = row.attrId == "label"*/)

    case InputType.number(start, end, step) =>
      InputWithValue(row.placeholderText, inputVarStr, "number", row.default/*, setFocus = row.attrId == "label"*/)
        .amend(
          minAttr := start.map(_.toString).getOrElse(""),
          maxAttr := end.map(_.toString).getOrElse(""),
          stepAttr := step.map(_.toString).getOrElse("")
        )

    case _ =>
      InputWithValue(row.placeholderText, inputVarStr, row.inputType.toString, row.default/*, setFocus = row.attrId == "label"*/)
