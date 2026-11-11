package org.jpablo.graphexplorer.viewer.utils

import scala.scalajs.js
import scala.util.control.NonFatal

/** The platform of the browser, for the keys that differ between platforms. */
object Platform:

  /** True when the platform name is the name of an Apple platform. These
    * platforms use ⌘ for the command key.
    *
    * The name can be `navigator.userAgentData.platform` ("macOS"),
    * `navigator.platform` ("MacIntel", "iPad") or `navigator.userAgent`.
    */
  def isAppleName(name: String): Boolean =
    val lower = name.toLowerCase
    List("mac", "iphone", "ipad", "ipod").exists(lower.contains)

  /** True when the event holds the command key of the platform: ⌘ (Meta) on a
    * Mac, Ctrl on each other platform.
    *
    * Ctrl is not the command key on a Mac. There, Ctrl+K is the key of
    * "delete to the end of the line" in a text field and in CodeMirror. Meta
    * is not the command key on Windows or Linux. There, it is the Windows key
    * or the Super key, and the system uses it.
    */
  def commandKey(metaKey: Boolean, ctrlKey: Boolean, isMac: Boolean): Boolean =
    if isMac then metaKey else ctrlKey

  /** The name of the platform, or "" when no browser gives one.
    *
    * `typeof` comes first, so a page with no `navigator` gives "". A read of
    * an undeclared global name throws ReferenceError.
    */
  private def platformName(): String =
    def text(value: js.Dynamic): Option[String] =
      if js.typeOf(value) == "string" && value.asInstanceOf[String].nonEmpty then Some(value.asInstanceOf[String])
      else None
    try
      if js.typeOf(js.Dynamic.global.navigator) == "undefined" then ""
      else
        val navigator = js.Dynamic.global.navigator
        val agentData = navigator.userAgentData
        val fromAgentData =
          if js.isUndefined(agentData) || agentData == null then None else text(agentData.platform)
        fromAgentData.orElse(text(navigator.platform)).orElse(text(navigator.userAgent)).getOrElse("")
    catch case NonFatal(_) => ""

  /** True when the browser runs on an Apple platform. The platform does not
    * change while the page runs, so the value is read one time.
    */
  lazy val isMac: Boolean = isAppleName(platformName())
