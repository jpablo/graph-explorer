package org.jpablo.graphexplorer.viewer.components.attributes.style

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.components.attributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.{AttrStatus, SelectionAttrValue}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*

class EnumSubAttr[A](
    getSubAttr:       StyleSubAttributes => AttrStatus[A],
    pathModify:       StyleSubAttributes => PathModify[StyleSubAttributes, AttrStatus[A]],
    valueOf:          String => A,
    hardDefault:      A,
    subAttributeVar:  Var[StyleSubAttributes],
    defaultSubAttrsS: Signal[StyleSubAttributes],
    getSubAttrsNow:   Signal[StyleSubAttributes] => StyleSubAttributes
):
  val getVar: Var[SelectionAttrValue] =
    subAttributeVar.zoomLazy(subAttrs =>
      getSubAttr(subAttrs).map(b => AttrValue(b.toString))
    )((subAttrs, userSelection) =>
      val attrStatus =
        userSelection match
          case Single(value) => AttrStatus.Single(valueOf(value.toString))
          case Multiple      => getSubAttr(getSubAttrsNow(defaultSubAttrsS))
          case Missing       => getSubAttr(getSubAttrsNow(defaultSubAttrsS))

      pathModify(subAttrs).setTo(attrStatus)
    )

  val getDefault: Signal[String] =
    defaultSubAttrsS.map(getSubAttr).map(_.toString)
end EnumSubAttr

// Rules:
// - global no style, local no style => default local: NoFill
// - global no style, local no style, user selects ColorFill => local style="filled"
// - global no style, local style="filled", user selects NoFill => local no style (removed)
// - global no style, local style="filled", user clicks reset => local no style (removed)

// - global style="filled", local no style => default local: ColorFill
// - global style="filled", local no style, user selects NoFill => local style=""
// - global style="filled", local style="" => default local: NoFill
// - global style="filled", local style="", user selects ColorFill  => local no style (removed)
// - global style="filled", local style="", user clicks reset  => local no style (removed)
