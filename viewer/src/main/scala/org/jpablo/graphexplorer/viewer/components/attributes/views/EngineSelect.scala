package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{InputAttribute, InputElement, combineDefaultString}
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Layout
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Single
import org.jpablo.graphexplorer.viewer.state.{DiagramLoadStatus, ViewerState}
import org.jpablo.graphexplorer.viewer.widgets.{SelectBox, SelectVariant}

/** The Engine row of the Diagram panel, with the choice "Manual" (ADR 0004).
  *
  * The engines of Graphviz are values of the `layout` attribute in the source. "Manual" is not: it is a mode that the
  * layout file stores. "Manual" sets the mode and freezes the places of the drawn nodes (Freeze), also on an empty
  * diagram. The select shows "Manual" while the diagram is in the manual mode, and the engine of the source in each
  * other case.
  *
  * A different choice on a manual diagram leaves the mode and removes the positions (Arrange), and then writes the
  * engine. When the source already has that engine, the source does not change.
  */
object EngineSelect:
  val manual = "manual"
  val auto   = "auto"

  private val manualTitle = "Keep each node at its place. Then a drag moves a node, and a drag on an arrow bends it."

  /** The cell of the Engine row of a DOT diagram. `row` is the row of the `layout` attribute. */
  def dot(state: ViewerState, row: InputAttribute): HtmlElement =
    val shown = state.hasFreeLayout.combineWith(row.combineDefaultString).map: (free, engine) =>
      if free then manual else engine
    SelectBox(
      SelectVariant.xs,
      cls   := "gx-engine-select",
      title := "The layout engine",
      // Manual is first, and a line puts it apart from the engines: it is a mode, not an engine.
      option("Manual", value := manual, title := manualTitle),
      hr(),
      row.options.map(o => option(o.name, value := o.value.toString)),
      controlled(
        value <-- shown,
        onChange.mapToValue --> { picked =>
          if picked == manual then state.freezeLayout()
          else
            if state.hasFreeLayoutNow then state.arrangeLayout()
            val engine = row.inputVar.now().getOrElse(Layout.default).toString
            if picked != engine then row.inputVar.set(Single(AttrValue(picked)))
        }
      )
    )

  /** The Engine row of a Mermaid flowchart: Mermaid lays it out, or the person does. A kind of diagram with no graph,
    * for example a sequence diagram, has no Engine row.
    */
  def mermaidRow(state: ViewerState): InputElement =
    val renderOnly = state.loadStatus.signal.map:
      case DiagramLoadStatus.RenderOnly(_) => true
      case _                               => false
    InputElement(
      div(
        cls := "attr-row gx-engine-row",
        div(
          cls := "flex items-center justify-start text-nowrap",
          div(title := "Engine — the layout of the nodes", "Engine")
        ),
        span(
          cls := "attr-value",
          SelectBox(
            SelectVariant.xs,
            cls   := "gx-engine-select",
            title := "The layout engine",
            // The same order as the Engine row of a DOT diagram: Manual, a line, and the automatic layout.
            option("Manual", value := manual, title := manualTitle),
            hr(),
            option("Automatic", value := auto, title := "Mermaid places each node"),
            controlled(
              value <-- state.hasFreeLayout.map(free => if free then manual else auto),
              onChange.mapToValue --> { picked =>
                if picked == manual then state.freezeLayout()
                else if state.hasFreeLayoutNow then state.arrangeLayout()
              }
            )
          )
        )
      ),
      hidden = renderOnly
    )
