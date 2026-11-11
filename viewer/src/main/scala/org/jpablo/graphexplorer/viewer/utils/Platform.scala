package org.jpablo.graphexplorer.viewer.utils

import org.scalajs.dom

/** The platform of the browser, for the keys that differ between platforms. */
object Platform:

  /** True when the name is the name of an Apple platform, for example a user
    * agent with "Macintosh" or "iPad". These platforms use ⌘ for the command
    * key.
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

  /** True when the browser runs on an Apple platform. Each target has a user
    * agent: the browser, the desktop webview and the test browser. The
    * platform does not change while the page runs, so the value is read one
    * time.
    */
  lazy val isMac: Boolean = isAppleName(dom.window.navigator.userAgent)
