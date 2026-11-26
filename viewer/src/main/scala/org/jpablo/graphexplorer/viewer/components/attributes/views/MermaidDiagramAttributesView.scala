package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.{MermaidBackgroundAttr, MermaidFontFamilyAttr, MermaidFontSizeAttr, MermaidThemeAttr}
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidInitDirective
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.SectionHeader
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{DotAttributeEnum, DotAttributeSimple, Label, Rankdir}
import org.jpablo.graphexplorer.viewer.models.AttributeId
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.{InputType, MenuDirection}
import org.jpablo.graphexplorer.viewer.widgets.InputType.{multiText, range}

/** Diagram-level attributes for Mermaid diagrams.
  *
  * Provides UI for editing:
  *   - Title (diagram label)
  *   - Direction (TB, LR, BT, RL)
  *   - Theme, font family and font size
  *   - Background
  *
  * The theme, the fonts and the background are in the init directive of the
  * source (`%%{init: {...}}%%`, see `MermaidInitDirective`). Thus the file
  * keeps them, and Mermaid reads them when it draws.
  */
def MermaidDiagramAttributesView(state: ViewerState) =
  val builder = RowBuilder(state.diagramAttributesUpdates, state.graphLayout)
  import builder.{row, rows}

  val labelRow =
    row(Label, multiText(), onReset = Some(""), label = Some("Title"), placeholder = Some("Enter diagram title"))

  div(
    idAttr := "mermaid-diagram-attributes-view",
    div(cls := "attributes-title flex-none", h2("Mermaid Diagram")),
    // The same sections as the DOT Diagram panel (DiagramAttributesView).
    VerticalAttributesView(
      id = "mermaid-diagram-attributes",
      rows = rows(
        SectionHeader("Title"),
        labelRow,
        SectionHeader("Layout"),
        row(Rankdir, InputType.menuWithExtra(4), label = Some("Direction")).copy(options = directionOptions),
        SectionHeader("Text"),
        row(MermaidTheme, InputType.select, label = Some("Theme")),
        row(MermaidFontFamily, InputType.dropdown, label = Some("Font")),
        row(MermaidFontSize, range(start = Some(8), end = Some(40), step = Some(1)), label = Some("Font size"), unit = Some("px")),
        SectionHeader("Canvas"),
        // Mermaid reads the background as a color, so the palette has no "none".
        row(MermaidBackground, InputType.menuWithExtra(mermaidBackgroundRows.length, MenuDirection.end), label = Some("Background"))
          .copy(options = mermaidBackgroundRows ++ colorOptions)
      )
    ).amend(cls := "flex-grow"),
    DiagramAboutView(state)
  )

private val mermaidBackgroundRows = lightRows8.filterNot(_.name == "none")

/** The `theme` of the init directive. */
object MermaidTheme extends DotAttributeEnum[String]:
  override def attrId: AttributeId = MermaidThemeAttr
  val label                        = "Theme"
  val default                      = "default"
  val values: Array[String]        = MermaidInitDirective.Themes.toArray
  override val valuesWithLabel     = values.map(v => (v.capitalize, v))

/** The `themeVariables.background` of the init directive. */
object MermaidBackground extends DotAttributeSimple[String]:
  override def attrId: AttributeId = MermaidBackgroundAttr
  val label                        = "Background"
  // The background of the default Mermaid theme.
  val default                      = "white"
  override val placeholderText     = "Enter background color here"

/** The `themeVariables.fontSize` of the init directive, in px. */
object MermaidFontSize extends DotAttributeSimple[Double]:
  override def attrId: AttributeId = MermaidFontSizeAttr
  val label                        = "Font size"
  // The font size of the Mermaid themes.
  val default                      = 16.0
  override val placeholderText     = "Enter font size here"

/** The `themeVariables.fontFamily` of the init directive. */
object MermaidFontFamily extends DotAttributeSimple[String]:
  override def attrId: AttributeId = MermaidFontFamilyAttr
  val label                        = "Font"
  // The font family of the Mermaid themes.
  val default                      = "\"trebuchet ms\", verdana, arial, sans-serif"
  override val placeholderText     = "Enter font name here"
  override val values: Array[String] =
    Array("trebuchet ms", "Arial", "Courier New", "Georgia", "Helvetica", "Times New Roman", "Verdana", "sans-serif", "serif", "monospace")
