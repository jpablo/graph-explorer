package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{AttributeHeader, InputAttribute}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Missing, Multiple}
import org.jpablo.graphexplorer.viewer.widgets.*

def AttributesView(
    id:   String,
    rows: Seq[AttributeRow]*
) =
  // TODO: Finish implementing this
  //  def getFonts(): js.Dynamic = js.Dynamic.global.window.queryLocalFonts().`then`(x => dom.console.log(x))

  // Group the headers with their content
  val groupedContent = buildGroupedContent(rows.flatten)

  div(
    idAttr := id,
    cls    := "attributes-view",
    groupedContent.map { (header, attrRows) =>
      fieldSet(
        cls := "fieldset",
        legend(cls := "fieldset-legend", header.title),
        for row <- attrRows
        yield AttributesViewRow(row).map(_.amend(cls("hidden") <-- row.hidden))
      )
    }
  )

private def AttributesViewRow(row: InputAttribute) =
  row.inputType match
    case InputType.multiText =>
      Seq(
        label(cls := "fieldset-label", inputLabel(row)),
        div(
          cls := "fieldset-input",
          buildInputCell(row)
        )
      )

    case InputType.range(s, e, step) =>
      Seq(
        label(
          cls := "fieldset-label fieldset-input flex justify-between",
          inputLabel(row),
          buildInputCell(row.copy(inputType = InputType.number(s, e, step)))
            .amend(cls := "w-20 text-[.6rem] input-ghost")
        ),
        div(
          cls := "fieldset-input",
          buildInputCell(row)
        )
      )

    case InputType.checkbox =>
      Seq(
        label(
          cls := "fieldset-label fieldset-input",
          span(row.label),
          buildInputCell(row)
        )
      )

    case _ =>
      Seq(
        label(
          cls := "fieldset-label fieldset-input flex justify-between",
          inputLabel(row),
          buildInputCell(row).amend(cls := "w-40")
        )
      )

private def inputLabel(row: InputAttribute): Div =
  val multipleValues = row.inputVar.signal.map(_ == Multiple)
  div(
    cls := "flex items-center justify-start",
    //
    div(cls("font-bold") <-- row.isChanged, row.label),
    //
    div(
      cls("w-6 flex items-center justify-center") <-- multipleValues.combineWith(row.isChanged).map(_ || _),
      child(
        span(title := s"Multiple values", i(cls := "bi bi-exclamation-triangle text-warning"))
      ) <-- multipleValues,
      child(
        a(
          title := s"reset ${row.label}",
          onClick --> row.inputVar.set(Missing),
          i(cls := "bi bi-x")
        ).tiny // .ghost //.circle
      ) <-- row.isChanged
    )
  )

private def buildGroupedContent(rows: Seq[AttributeRow]): Seq[(AttributeHeader, Seq[InputAttribute])] =
  var result: List[(AttributeHeader, List[InputAttribute])] = List.empty
  var currentHeader: Option[AttributeHeader] = None
  var currentAttributes: List[InputAttribute] = List.empty

  for row <- rows do
    row match
      case header: AttributeHeader =>
        if currentHeader.nonEmpty then
          result            = (currentHeader.get, currentAttributes.reverse) :: result
          currentAttributes = List.empty
        currentHeader = Some(header)

      case attr: InputAttribute =>
        currentAttributes = attr :: currentAttributes

  // Add the last group if exists
  if currentHeader.nonEmpty then
    result = (currentHeader.get, currentAttributes.reverse) :: result

  result.reverse.map((h, attrs) => (h, attrs))

private def buildInputCell(row: InputAttribute) =
  row.inputType match
    case InputType.selectWithPreviewGrid => SelectWithPreviewGrid(row)
    case InputType.selectWithPreview     => SelectWithPreview(row)
    case InputType.select                => SelectWithValue(row)
    case InputType.checkbox              => Checked(row)
    case InputType.multiText             => TextAreaWithValue(row)
    case _                               => InputWithValue(row)
