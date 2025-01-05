package org.jpablo.graphexplorer.viewer.domUtils

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.{BooleanAsAttrPresenceCodec, StringAsIsCodec}
import org.scalajs.dom.HTMLDialogElement

import scala.scalajs.js


val details = htmlTag("details")
val summary = htmlTag("summary")
val dialog = htmlTag[HTMLDialogElement]("dialog")

val open = htmlAttr("open", BooleanAsAttrPresenceCodec)
val dataTip = htmlAttr("data-tip", StringAsIsCodec)
val dataTabId = htmlAttr("data-tab-id", StringAsIsCodec)
val name = htmlAttr("name", StringAsIsCodec)
val ariaLabel = htmlAttr("aria-label", StringAsIsCodec)

val autocomplete = htmlProp("autocomplete", StringAsIsCodec)

val gridColumn = styleProp("grid-column")


extension (doc: dom.HTMLDocument)
  def elementsFromPoint(x: Double, y: Double): js.Array[dom.Element] =
    doc.asInstanceOf[js.Dynamic]
      .elementsFromPoint(x, y)
      .asInstanceOf[js.Array[dom.Element]]
