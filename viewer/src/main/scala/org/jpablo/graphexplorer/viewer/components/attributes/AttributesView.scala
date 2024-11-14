package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.Mods
import org.jpablo.graphexplorer.viewer.widgets.{InputWithValue, InputType, SelectWithValue, Checked}

def AttributesView(
    id:    String,
    title: String,
    attrs: Var[Map[String, String]],
    rows:  Seq[AttributeRow]
) =
  // TODO: Finish implementing this
//  def getFonts(): js.Dynamic =
//    js.Dynamic.global.window.queryLocalFonts().`then`(x => dom.console.log(x))

  div(
    idAttr := id,
    h3(cls := "font-bold text-lg", title),
    hr(),
    table(
      cls := "table mt-3",
      tbody(
        for row <- rows
        yield
          val inputVarStr = attrs.zoomLazy(_.get(row.attrId))((a, value) => value.fold(a)(s => a + (row.attrId -> s)))
          tr(
            td(row.label),
            td(
              row.inputType match
                case InputType.select =>
                  SelectWithValue(row.options, inputVarStr, row.default)
                
                case InputType.checkbox =>
                  val inputVarBool = inputVarStr.zoomLazy(_.map(_.contains(true.toString)))((_, b) => b.map(_.toString))
                  Checked(row.placeholderText, inputVarBool, row.default == true.toString)
                
                case _ =>
                  InputWithValue(row.placeholderText, inputVarStr, row.inputType, row.default)
            ),
            td()
          )
      )
    )
  )
