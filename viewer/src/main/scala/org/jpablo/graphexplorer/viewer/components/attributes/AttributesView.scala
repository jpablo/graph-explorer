package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import AttributeType.*
import org.jpablo.graphexplorer.viewer.models.Attributes
import org.jpablo.graphexplorer.viewer.widgets.*
import com.raquo.laminar.api.features.unitArrows

def AttributesView(
    id:    String,
    titleStr: String,
    attrs: Var[Attributes],
    defaults: Option[Signal[Attributes]],
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
          thead(for h <- headers yield tr(th(colSpan := 3, h.title)))

        case Right(attrRows) =>
          tbody(
            for row <- attrRows yield
              val attrVar = row.inputValue.getOrElse(makeInputVar(row.attrId, attrs))
              val default = getDefaultValue(defaults, row)
              val isChanged = attrVar.signal.combineWith(default).map((attr, d) => attr.exists(_.toString != d))
              tr(
                td(
                  cls("font-bold") <-- isChanged,
                  span(cls := "me-1", row.label),
                  child <-- isChanged.map(c =>
                    if c then
                      Button(
                        title := s"reset ${row.label}",
                        onClick --> attrVar.set(None),
                        i(cls := "bi bi-x")
                      ).tiny.ghost.circle
                    else
                      ""
                  )

                ),
                td(buildInputCell(titleStr, row, attrVar, default))
              )
          )
    )
  )

// uses the global default if present, otherwise uses the (hardcoded) default value.
private def getDefaultValue(defaults: Option[Signal[Attributes]], row: AttributeRow): Signal[String] =
  defaults
    .map(_.map(_.get(row.attrId).map(_.toString).getOrElse(row.default)))
    .getOrElse(Signal.fromValue(row.default))


private def buildGroups(rows: Seq[AttributeType]) =
  var rr: List[Either[List[AttributeHeader], List[AttributeRow]]] = List.empty

  for rowType <- rows do
    (rr, rowType) match
      case (Nil, h: AttributeHeader)            => rr = List(Left(List(h)))
      case (Left(hs) :: t, h: AttributeHeader)  => rr = Left(h :: hs) :: t
      case (Right(rs) :: t, h: AttributeHeader) => rr = Left(List(h)) :: Right(rs) :: t

      case (Nil, r: AttributeRow)            => rr = List(Right(List(r)))
      case (Left(hs) :: t, r: AttributeRow)  => rr = Right(List(r)) :: Left(hs) :: t
      case (Right(rs) :: t, r: AttributeRow) => rr = Right(r :: rs) :: t

  rr.map:
    case Left(hs)  => Left(hs.reverse)
    case Right(rs) => Right(rs.reverse)
  .reverse

private def makeInputVar(attrId: String, attrsVar: Var[Attributes]): Var[Option[AttrValue]] =
  attrsVar.zoomLazy(
    _.values.get(attrId)
  )((attrs, value) =>
    value match
      case None    => attrs - attrId // Remove override, will fall back to root value
      case Some(v) => attrs + (attrId -> v) // Set override
  )

private def buildInputCell(parent: String, row: AttributeRow, attrVar: Var[Option[AttrValue]], default: Signal[String]) =
  row.inputType match
    case InputType.select =>
      SelectWithValue(parent, row.attrId, row.options, attrVar, default)

    case InputType.checkbox =>
      val inputVarBool =
        attrVar.zoomLazy(_.map(_.toString.contains(true.toString)))((_, b) => b.map(v => AttrValue(v.toString)))
      Checked(row.placeholderText, inputVarBool, row.default == true.toString)

    case InputType.`multiText` =>
      TextAreaWithValue(row.placeholderText, attrVar, row.default /*, setFocus = row.attrId == "label"*/ )

    case InputType.number(start, end, step) =>
      InputWithValue(row.placeholderText, attrVar, "number", row.default /*, setFocus = row.attrId == "label"*/ )
        .amend(
          minAttr  := start.map(_.toString).getOrElse(""),
          maxAttr  := end.map(_.toString).getOrElse(""),
          stepAttr := step.map(_.toString).getOrElse("")
        )

    case _ =>
      InputWithValue(
        row.placeholderText,
        attrVar,
        row.inputType.toString,
        row.default /*, setFocus = row.attrId == "label"*/
      )
