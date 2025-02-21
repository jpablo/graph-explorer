package org.jpablo.graphexplorer.viewer.components.attributes.style

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.softwaremill.quicklens.PathModify
import org.jpablo.graphexplorer.viewer.components.attributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue

class BooleanSubAttr(
    getSubAttr:       StyleSubAttributes => Boolean,
    pathModify:       StyleSubAttributes => PathModify[StyleSubAttributes, Boolean],
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
          Some(pathModify(defaultSubAttrs).setTo(attrValue.isTrue))

        case (Some(subAttrs), None) =>
          // Copy the default sub attributes to the current one, otherwise they will be overridden by "false" (implicit meaning of a missing value)
          val defaultSubAttrs = getSubAttrsNow(defaultSubAttrsS)
          Some(pathModify(subAttrs).setTo(getSubAttr(defaultSubAttrs)))

        case (Some(subAttrs), Some(attrValue)) =>
          Some(pathModify(subAttrs).setTo(attrValue.isTrue))
    )

  val getDefault: Signal[String] =
    defaultSubAttrsS.map(getSubAttr).map(_.toString)
end BooleanSubAttr
