package org.jpablo.graphexplorer.viewer.components.attributes.views.toolbarViews

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveElement.Base
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{AttributeHeader, InputAttribute}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Missing, Multiple}
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.MenuEntry.MenuOption

def HorizontalAttributesView(
    showHeaders: Boolean = true,
    rows:        Seq[AttributeRow],
    extra:       Seq[AttributeRow] = Seq.empty
) =
  div(
    cls := "horizontal-attributes-view",
    buildFieldSets(rows, showHeaders)
  )

private def AttributesViewRow(row: InputAttribute) =
  row.inputType match
    case InputType.multiText =>
      Seq(
        label(cls := "fieldset-label", inputLabel(row)),
        div(cls   := "fieldset-input", buildInputCell(row))
      )

    case InputType.range(s, e, step) =>
      Seq(
        label(
          cls := "fieldset-label fieldset-input flex justify-between",
          inputLabel(row),
          buildInputCell(row.copy(inputType = InputType.number(s, e, step)))
            .amend(cls := "w-16 text-[.6rem] input-ghost")
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
      if row.singleRow then
        Seq(
          label(
            cls := "fieldset-label fieldset-input flex justify-between",
            inputLabel(row),
            buildInputCell(row).amend(cls := "w-40")
          )
        )
      else
        Seq(
          label(cls := "fieldset-label", inputLabel(row)),
          div(cls   := "fieldset-input", buildInputCell(row))
        )

private def inputLabel(row: InputAttribute): Div =
  val multipleValues = row.inputVar.signal.map(_ == Multiple)
  div(
    cls := "flex items-center justify-start",
    div(cls("font-bold") <-- row.isChanged, row.label),
    div(
      cls("w-6 flex items-center justify-center") <-- multipleValues.combineWithFn(row.isChanged)(_ || _),
      child(span(title := s"Multiple values", i(cls := "bi bi-exclamation-triangle text-warning"))) <-- multipleValues,
      child(a(title := s"reset ${row.label}", onClick --> row.inputVar.set(Missing), i(cls := "bi bi-x")).tiny) <-- row.isChanged
    )
  )

private def buildFieldSets(rows: Seq[AttributeRow], showHeaders: Boolean = true) =
  buildGroupedContent(rows).flatMap: (_, attrRows) =>
    for row <- attrRows
    yield fieldSet(
      cls := "fieldset",
      AttributesViewRow(row).map(_.amend(cls("hidden") <-- row.hidden))
    )

/** Takes a flat sequence of mixed headers and rows and groups them by (optional) header.
  */
private def buildGroupedContent(rows: Seq[AttributeRow]): Seq[(Option[AttributeHeader], Seq[InputAttribute])] =
  var result: List[(Option[AttributeHeader], List[InputAttribute])] = Nil
  var currentHeader: Option[AttributeHeader]                        = None
  var currentAttributes: List[InputAttribute]                       = Nil

  for row <- rows do
    row match
      case header: AttributeHeader =>
        if currentAttributes.nonEmpty then
          // Add current attributes with their header (or None if no header)
          result ::= currentHeader -> currentAttributes.reverse
          currentAttributes = Nil

        // Start a new group with the new header
        currentHeader = Some(header)

      case attr: InputAttribute =>
        currentAttributes ::= attr

  // Add the last group if it has attributes
  if currentAttributes.nonEmpty then
    result ::= currentHeader -> currentAttributes.reverse

  result.reverse.map((h, attrs) => (h, attrs))

private def buildInputCell(row: InputAttribute) =
  row.inputType match
    case InputType.menuWithExtra(initial, dir, cardClass)   => MenuWithExtraDropdown(row, initial, dir, cardClass)
    case InputType.currentValueWithSelector(dir, cardClass) => CurrentValueWithSelector(row, dir, cardClass)
    case InputType.dropdown =>
      val selectedOption: Signal[Base] =
        row.combineDefaultString.map: attrValueStr =>
          val selected = row.options.find(o => o.value.toString == attrValueStr).flatMap(_.elem).map(_())
          if selected.isEmpty then
            pprint.log(attrValueStr)
            pprint.log(row.options.map(o => o.value.toString))
          selected.getOrElse(span("?"))

      Dropdown(
        title = emptyMod,
        options = Signal.fromValue(
          row.options.map(o =>
            MenuOption(
              elem = o.elem.getOrElse(() => span(o.name))(),
              value = o.value,
              description = Some(o.name),
              shortcut = None
            )
          )
        ),
        onClickHandler = _ --> (attrValue => row.inputVar.set(attrValue)),
        icon = child <-- selectedOption,
        menuCls = "items-center"
      ).amend(cls := "dropdown-center ml-[-1px]")

    case InputType.select    => SelectWithValue(row)
    case InputType.checkbox  => Checked(row)
    case InputType.multiText => TextAreaWithValue(row)
    case _                   => InputWithValue(row)
