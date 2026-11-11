package org.jpablo.graphexplorer.viewer.utils

import org.scalajs.dom

import scala.scalajs.js

/** Key events for the specs. */
object KeyEvents:

  /** A real `keydown` event that bubbles and that a handler can cancel. After
    * a handler runs, `defaultPrevented` tells if it took the key from the
    * browser. A real event has each field that the code can read, for example
    * `repeat`.
    */
  def keyDown(
      key:    String,
      meta:   Boolean = false,
      ctrl:   Boolean = false,
      shift:  Boolean = false,
      alt:    Boolean = false,
      repeat: Boolean = false
  ): dom.KeyboardEvent =
    val init = new dom.KeyboardEventInit {}
    init.key = key
    init.metaKey = meta
    init.ctrlKey = ctrl
    init.shiftKey = shift
    init.altKey = alt
    init.asInstanceOf[js.Dynamic].updateDynamic("repeat")(repeat)
    init.bubbles = true
    init.cancelable = true
    dom.KeyboardEvent("keydown", init)
