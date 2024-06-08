package org.jpablo.typeexplorer.viewer.domUtils

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.{BooleanAsAttrPresenceCodec, StringAsIsCodec}
import org.scalajs.dom.HTMLDialogElement

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
