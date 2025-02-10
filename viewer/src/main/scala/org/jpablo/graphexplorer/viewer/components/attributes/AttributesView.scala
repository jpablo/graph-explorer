package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import AttributeRow.*
import org.jpablo.graphexplorer.viewer.widgets.*
import com.raquo.laminar.api.features.unitArrows

def AttributesView(
    id:       String,
    titleStr: String,
    rows:     Seq[AttributeRow]*
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
          thead(for h <- headers yield tr(th(colSpan := 3, h.title)))

        case Right(attrRows) =>
          tbody(
            for row <- attrRows yield
              val isChanged =
                row.inputVar.signal
                  .combineWith(row.default)
                  .map: (attr, d) =>
                    // if row.attrId.contains("style") then
//                    dom.console.log(s"id: ${row.attrId}, attr: $attr, default: $d")
                    attr.exists(_.toString != d)
              tr(
                td(
                  cls := "w-32",
                  cls("font-bold") <-- isChanged,
                  span(cls := "me-1", row.label),
                  child <-- isChanged.map(c =>
                    if c then
                      Button(
                        title := s"reset ${row.label}",
                        onClick --> row.inputVar.set(None),
                        i(cls := "bi bi-x")
                      ).tiny.ghost.circle
                    else
                      ""
                  )
                ),
                td(buildInputCell(row))
              )
          )
    )
  )

private def buildGroups(rows: Seq[AttributeRow]) =
  var rr: List[Either[List[AttributeHeader], List[InputAttribute]]] = List.empty

  for rowType <- rows do
    (rr, rowType) match
      case (Nil, h: AttributeHeader)            => rr = List(Left(List(h)))
      case (Left(hs) :: t, h: AttributeHeader)  => rr = Left(h :: hs) :: t
      case (Right(rs) :: t, h: AttributeHeader) => rr = Left(List(h)) :: Right(rs) :: t

      case (Nil, r: InputAttribute)            => rr = List(Right(List(r)))
      case (Left(hs) :: t, r: InputAttribute)  => rr = Right(List(r)) :: Left(hs) :: t
      case (Right(rs) :: t, r: InputAttribute) => rr = Right(r :: rs) :: t

  rr.map:
    case Left(hs)  => Left(hs.reverse)
    case Right(rs) => Right(rs.reverse)
  .reverse

private def buildInputCell(row: InputAttribute) =
  row.inputType match
    case InputType.select =>
      SelectWithValue(row.options, row.inputVar, row.default)

    case InputType.checkbox =>
      val inputVarBool =
        row.inputVar.zoomLazy(_.map(_.toString.contains(true.toString)))((_, b) => b.map(v => AttrValue(v.toString)))
      Checked(row.placeholder, inputVarBool, row.default.map(_ == true.toString))

    case InputType.`multiText` =>
      TextAreaWithValue(row.placeholder, row.inputVar)

    case InputType.number(start, end, step) =>
      InputWithValue(row.placeholder, row.inputVar, "number", row.default)
        .amend(
          minAttr  := start.map(_.toString).getOrElse(""),
          maxAttr  := end.map(_.toString).getOrElse(""),
          stepAttr := step.map(_.toString).getOrElse("")
        )

    case _ =>
      InputWithValue(
        row.placeholder,
        row.inputVar,
        row.inputType.toString,
        row.default
      )
