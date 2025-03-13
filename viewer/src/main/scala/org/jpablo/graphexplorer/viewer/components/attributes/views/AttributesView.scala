package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{AttributeHeader, InputAttribute}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Missing, Multiple}
import org.jpablo.graphexplorer.viewer.widgets.*

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
            for
              row <- attrRows
              multipleValues = row.inputVar.signal.map(_ == Multiple)
            yield tr(
              td(
                cls := "w-32 align-middle whitespace-nowrap",
                div(
                  cls := "flex items-center gap-1",
                  span(row.label),
                  div(
                    cls := "w-6", // Fixed width space for the reset button
                    child(
                      span(
                        title := s"Multiple values",
                        i(cls := "bi bi-exclamation-triangle")
                      )
                    ) <-- multipleValues,
                    child(
                      Button(
                        title := s"reset ${row.label}",
                        onClick --> row.inputVar.set(Missing),
                        i(cls := "bi bi-x")
                      ).tiny.ghost.circle
                    ) <-- row.isChanged
                  )
                )
              ),
              td(cls := "align-middle", buildInputCell(row))
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

    case InputType.selectWithPreviewGrid =>
      SelectWithPreviewGrid(row)

    case InputType.selectWithPreview => SelectWithPreview(row)

    case InputType.select => SelectWithValue(row)

    case InputType.checkbox => Checked(row)

    case InputType.multiText =>
      TextAreaWithValue(row)

    case InputType.number(start, end, step) =>
      InputWithValue(row, "number")
        .amend(
          minAttr  := start.map(_.toString).getOrElse(""),
          maxAttr  := end.map(_.toString).getOrElse(""),
          stepAttr := step.map(_.toString).getOrElse("")
        )

    case InputType.range(start, end, step) =>
      InputWithValue(row, "number", border = false)
        .amend(
          tpe      := "range",
          cls      := "range range-sm input-ghost",
          minAttr  := start.map(_.toString).getOrElse(""),
          maxAttr  := end.map(_.toString).getOrElse(""),
          stepAttr := step.map(_.toString).getOrElse("")
        )

    case _ =>
      InputWithValue(row, row.inputType.toString)
