package org.jpablo.graphexplorer.viewer.components.attributes.style

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.components.attributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue


class EnumSubAttr[A](
  getSubAttr:       StyleSubAttributes => A,
  pathModify:       StyleSubAttributes => PathModify[StyleSubAttributes, A],
  valueOf:          String => A,
  hardDefault:      A,
  subAttributeVar:  Var[Option[StyleSubAttributes]],
  defaultSubAttrsS: Signal[StyleSubAttributes],
  getSubAttrsNow:   Signal[StyleSubAttributes] => StyleSubAttributes
):
  val getVar: Var[Option[AttrValue]] =
    subAttributeVar.zoomLazy(subAttrs =>
      subAttrs.map(getSubAttr).map(b => AttrValue(b.toString))
    )((subAttrs, attrValueO) =>

      (subAttrs, attrValueO) match
        case (None, None) => None

        case (None, Some(attrValue)) =>
          val defaultSubAttrs = getSubAttrsNow(defaultSubAttrsS)
          Some(pathModify(defaultSubAttrs).setTo(valueOf(attrValue.toString)))

        case (Some(subAttrs), None) =>
          val defaultSubAttrs = getSubAttrsNow(defaultSubAttrsS)
          Some(pathModify(subAttrs).setTo(getSubAttr(defaultSubAttrs)))

        case (Some(subAttrs), Some(attrValue)) =>
          Some(pathModify(subAttrs).setTo(valueOf(attrValue.toString)))
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
