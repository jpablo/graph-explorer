package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.{InputVariant}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.{AttributeRow, RowBuilder}
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputAttribute, InputElement, SectionHeader}
import org.jpablo.graphexplorer.viewer.widgets.*

def VerticalCardWithPreview(builder: RowBuilder, id: String, iAttrs: InputAttribute*) =
  DropdownHeader(
    div(
      cls := "flex gap-4 bg-base-100 rounded-box px-3",
      iAttrs.map(ia => child <-- ia.selectedOption)
    ),
    body =
      div(
        cls := "card card-border card-xs bg-base-100 shadow-sm attr-card",
        div(
          cls := "card-body",
          VerticalAttributesView(
            id = id,
            rows = builder.rows(iAttrs*)
          )
        )
      )
  )

// title is a Modifier so callers can hand it an icon instead of a word — the toolbar's
// overflow trigger says "more of these" with a glyph, not with the label "extra".
def VerticalCardWithButton(id: String, title: Modifier.Base, iars: Seq[AttributeRow]) =
  DropdownHeader(
    title,
    body =
      div(
        cls := "card card-border card-xs bg-base-100 shadow-sm attr-card",
        div(
          cls := "card-body",
          VerticalAttributesView(
            id = id,
            rows = iars
          )
        )
      )
  ).amend(
    // Opens leftwards. This is the OVERFLOW card, so it is always the last control in the
    // bar; hanging its left edge off the trigger and growing right ran it towards the edge
    // of the window, and the card is wider than it used to be. Anchoring the right edge
    // instead grows it back over the bar, which has room by construction. The other cards
    // sit mid-bar and are fine growing rightwards.
    cls := "dropdown-end"
  )

def VerticalAttributesView(
    id:    String,
    rows:  Seq[AttributeRow],
    extra: Seq[AttributeRow] = Seq.empty
) =
  // TODO: Finish implementing this
  //  def getFonts(): js.Dynamic = js.Dynamic.global.window.queryLocalFonts().`then`(x => dom.console.log(x))
  val extraVisible = Var(false)
  div(
    idAttr := id,
    cls    := "attributes-view",
    LabeledCheckbox(id + "extra-visible", "extra", extraVisible).amend(
      cls           := "flex justify-end mr-2",
      cls("hidden") := extra.isEmpty
    ),
    children(buildFieldSets(rows)) <-- extraVisible.signal.map(!_),
    children(buildFieldSets(extra)) <-- extraVisible.signal
  )

/** A control showing at least this many options inline stops being a value beside a label and
  * becomes a set to scan — a palette. Those get the row to themselves.
  *
  * Deliberately a count rather than a list of attribute names: what makes the colour swatches
  * need width is that there are eight of them, so the rule should read the same fact. Grow the
  * palette and the layout follows on its own; the alignment (2–3) and direction (4) pickers
  * stay beside their labels.
  */
private val InlineOptionsNeedingFullWidth = 5

/** One attribute, as a row.
  *
  * The default is two columns — label left, control right, on ONE line. Stacking the label
  * above its control doubled the height of every row, which is what pushed a twelve-control
  * panel into scrolling; a single column of left-aligned labels is also the thing the eye
  * scans when hunting for a control.
  *
  * Two things stack instead: a multi-line text box, and a palette — both because they are
  * unusable squeezed into the value column.
  *
  * The wrapper is a plain `div`, and MUST NOT go back to being a `<label>`. A label with no
  * `for` forwards clicks to its first labelable descendant, and the reset button in
  * `InputLabelWithResetButton` is exactly that — it is a `<button>`, and it comes first in
  * tree order. So on any row that has been changed, one real click on an inert part of the
  * row (the caption, a border-style swatch — a `div`, not interactive content) dispatched a
  * SECOND click at the reset button, which writes `Missing` and deletes the attribute the
  * user just set. The panel was never labelling the control anyway: with the button present
  * it named the reset button, so the input had no accessible name at all. Names now come from
  * `aria.label` in [[buildInputCell]], which is both correct and cannot be re-targeted.
  */
private def AttributesViewRow(attRow: AttributeRow) =
  attRow match
    case _: InputElement  => Seq.empty
    case _: SectionHeader => Seq.empty
    case row: InputAttribute =>
      row.inputType match
        case InputType.multiText(_) =>
          Seq(
            div(cls := "fieldset-label", InputLabelWithResetButton(row)),
            div(cls := "fieldset-input", buildInputCell(row))
          )

        case InputType.menuWithExtra(inline, _, _) if inline >= InlineOptionsNeedingFullWidth =>
          Seq(
            div(
              cls := "attr-row attr-row-stacked",
              InputLabelWithResetButton(row),
              span(cls := "attr-value", buildInputCell(row))
            )
          )

        // A slider keeps its track, but the number beside it is the editable one: a track
        // alone cannot express "exactly 0.5", and the raw value is what people came to set.
        case InputType.range(s, e, step) =>
          Seq(
            div(
              // attr-row-range: a track needs real width to be draggable. In the side
              // panel it shares the line with its label; in the toolbar's 192px popup
              // cards that left a 31px slider, so there it stacks instead.
              cls := "attr-row attr-row-range",
              InputLabelWithResetButton(row),
              span(
                cls := "attr-value flex items-center gap-1",
                // Sizing lives in CSS (.attr-value): the track takes what the readout
                // leaves. Utilities cannot express it here — daisyUI's own .input rule
                // outranks them and the number would swallow the column.
                buildInputCell(row).amend(cls := "gx-range-nano"),
                // Two controls for one attribute: the readout takes a distinct name so the
                // pair does not announce as the same thing twice.
                buildInputCell(row.copy(inputType = InputType.number(s, e, step)))
                  .amend(InputVariant.ghost, cls := "text-[.6rem] text-right", aria.label := s"${row.label} value"),
                row.unit.map(u => span(cls := "attr-unit", u))
              )
            )
          )

        case _ =>
          Seq(
            div(
              cls := "attr-row",
              InputLabelWithResetButton(row),
              span(cls := "attr-value", buildInputCell(row))
            )
          )

/** Renders a row list, honouring the section breaks in it.
  *
  * A SectionHeader owns every row up to the next one, and hides itself when all of them are
  * hidden — otherwise a heading like "Title" would sit over an empty gap whenever its
  * attributes did not apply to the current layout.
  */
def buildFieldSets(rows: Seq[AttributeRow]): Seq[HtmlElement] =
  val sections = splitIntoSections(rows)
  sections.flatMap: (header, sectionRows) =>
    val inputs = sectionRows.collect { case ia: InputAttribute => ia }
    val fieldSets =
      inputs.map: row =>
        fieldSet(
          cls := "fieldset",
          AttributesViewRow(row).map(_.amend(cls("hidden") <-- row.hidden))
        )
    header.toSeq.map(h => sectionHeading(h, inputs)) ++ fieldSets

private def sectionHeading(header: SectionHeader, rows: Seq[InputAttribute]) =
  val allHidden =
    if rows.isEmpty then Signal.fromValue(true)
    else Signal.combineSeq(rows.map(_.hidden)).map(_.forall(identity))
  div(
    cls := "attributes-section",
    cls("hidden") <-- allHidden,
    span(header.title)
  )

/** Groups rows by the section headers between them; rows before the first header (or in a
  * list with no headers at all) form one leading group with no heading.
  */
private def splitIntoSections(rows: Seq[AttributeRow]): Seq[(Option[SectionHeader], Seq[AttributeRow])] =
  val (leading, rest) = rows.span { case _: SectionHeader => false; case _ => true }
  val grouped =
    rest
      .foldLeft(List.empty[(Option[SectionHeader], List[AttributeRow])]):
        case (acc, h: SectionHeader) => (Some(h), Nil) :: acc
        case (Nil, row)              => (None, row :: Nil) :: Nil
        case ((h, rs) :: tail, row)  => (h, row :: rs) :: tail
      .map((h, rs) => (h, rs.reverse))
      .reverse
  if leading.isEmpty then grouped else (None, leading) +: grouped

private def buildInputCell(row: InputAttribute): HtmlElement =
  val cell = row.inputType match
    case InputType.menuWithExtra(initial, dir, cardClass) => MenuWithExtraDropdown(row, initial, dir, cardClass)
    case InputType.dropdown                               => DropdownForRow(row)
    case InputType.select                                 => SelectWithValue(row)
    case InputType.checkbox                               => Checked(row)
    case InputType.multiText(setFocus)                    => TextAreaWithValue(row, setFocus = setFocus)
    // The fallthrough builds an <input type={the input type}>, so an input type this match
    // does not name renders as a text box with a nonsense `type` rather than failing. Add
    // the case; do not rely on the default.
    case _ => InputWithValue(row)
  // The caption is a sibling div, not a wrapping `<label>` (see AttributesViewRow for why it
  // cannot be one), so the accessible name has to travel with the control itself.
  cell.amend(aria.label := row.label)
