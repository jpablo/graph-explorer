package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import AttributeRow.*
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.widgets.*
import com.raquo.laminar.api.features.unitArrows

def AttributesView(
    id:       String,
    titleStr: String,
    attrs:    Var[Attributes],
    defaults: Option[Signal[Attributes]],
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
              val default = getDefaultValue(defaults, row)
              val isChanged =
                row.inputValue.signal
                  .combineWith(default)
                  .map: (attr, d) =>
                    // if row.attrId.contains("style") then
                    println(s"id: ${row.attrId}, attr: $attr, default: $d")
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
                        onClick --> row.inputValue.set(None),
                        i(cls := "bi bi-x")
                      ).tiny.ghost.circle
                    else
                      ""
                  )
                ),
                td(buildInputCell(titleStr, row, row.inputValue, default))
              )
          )
    )
  )

// uses the global default if present, otherwise uses the (hardcoded) default value.
private def getDefaultValue(globalDefaults: Option[Signal[Attributes]], row: InputAttribute): Signal[String] =
  globalDefaults
    .map(_.map { globalAttributes =>
      pprint.log(row.attrId)
      pprint.log(row.default)
      pprint.log(globalAttributes)
      pprint.log(globalAttributes.get(row.attrId))
      val r = globalAttributes.get(row.attrId).map(_.toString).getOrElse(row.default)
      pprint.log(r)
      r
    })
    .getOrElse(Signal.fromValue(row.default))

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

private def buildInputCell(
    parent:  String,
    row:     InputAttribute,
    attrVar: Var[Option[AttrValue]],
    default: Signal[String]
) =
  row.inputType match
    case InputType.select =>
      SelectWithValue(parent, row.attrId, row.options, attrVar, default)

    case InputType.checkbox =>
      val inputVarBool =
        attrVar.zoomLazy(_.map(_.toString.contains(true.toString)))((_, b) => b.map(v => AttrValue(v.toString)))
      Checked(row.placeholder, inputVarBool, row.default == true.toString)

    case InputType.`multiText` =>
      TextAreaWithValue(row.placeholder, attrVar, row.default /*, setFocus = row.attrId == "label"*/ )

    case InputType.number(start, end, step) =>
      InputWithValue(row.placeholder, attrVar, "number", default /*, setFocus = row.attrId == "label"*/ )
        .amend(
          minAttr  := start.map(_.toString).getOrElse(""),
          maxAttr  := end.map(_.toString).getOrElse(""),
          stepAttr := step.map(_.toString).getOrElse("")
        )

    case _ =>
      InputWithValue(
        row.placeholder,
        attrVar,
        row.inputType.toString,
        default /*, setFocus = row.attrId == "label"*/
      )
