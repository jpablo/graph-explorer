package org.jpablo.graphexplorer.viewer.components.attributes.style

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.softwaremill.quicklens.PathModify
import org.jpablo.graphexplorer.viewer.components.attributes.StyleSubAttributes
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.models.AttrStatus
import org.jpablo.graphexplorer.viewer.models.AttrStatus.*
import org.jpablo.graphexplorer.viewer.models.SelectionAttrValue

class BooleanSubAttr(
    getSubAttr:       StyleSubAttributes => AttrStatus[Boolean],
    pathModify:       StyleSubAttributes => PathModify[StyleSubAttributes, AttrStatus[Boolean]],
    subAttributeVar:  Var[StyleSubAttributes],
    defaultSubAttrsS: Signal[StyleSubAttributes],
    getSubAttrsNow:   Signal[StyleSubAttributes] => StyleSubAttributes
):
  // TODO: refactor this (combine AttrStatus and SelectionAttrValue)
  val getVar: Var[SelectionAttrValue] =
    subAttributeVar.zoomLazy(subAttrs =>
      getSubAttr(subAttrs).map(b => AttrValue(b.toString))
    )((subAttrs, userSelection) =>
      val attrStatus =
        userSelection match
          case Single(value) => Single(value.isTrue)
          case Multiple      => getSubAttr(getSubAttrsNow(defaultSubAttrsS))
          case Missing       => getSubAttr(getSubAttrsNow(defaultSubAttrsS))
      pathModify(subAttrs).setTo(attrStatus)
    )

  val getDefault: Signal[String] =
    defaultSubAttrsS.map(getSubAttr).map(_.toString)
end BooleanSubAttr
