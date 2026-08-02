package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.{EventStream, Signal}
import org.scalajs.dom

import scala.scalajs.js

/** How long the pipeline waits after the last keystroke before re-running.
  *
  * Typing used to re-parse the document and re-lay the whole graph out on EVERY
  * character, synchronously, before the browser was allowed to paint. Measured
  * on a 600-node `circo` graph: ~250ms per keystroke, and Chrome reported
  * 1128ms on a larger one — that number IS the app's Interaction to Next Paint,
  * and "poor" on its own.
  *
  * What is paced is the PARSE and the RENDER, never the text. `sourceText`
  * still updates on every keystroke, so the document that persistence observes
  * is always current; only the expensive view of it lags. Delaying the text
  * instead was tried and is a data-loss bug: it leaves typed characters living
  * only inside the editor, and a route change unmounts the editor — with its
  * pending timer — before they are ever written. Measured, not theorised: a
  * marker typed 30ms before clicking "Library" never reached localStorage.
  *
  * The delay is the cost of the LAST run, clamped, because a fixed number
  * cannot serve both ends of this app's range and document length is a poor
  * proxy for layout cost — a small `circo` graph outruns a large `dot` one. A
  * trivial diagram renders in a few ms and stays live under the floor; an
  * expensive one waits until typing actually pauses. Self-tuning also means no
  * threshold to keep up to date as the layout engines change.
  */
object EditorPacing:

  /** Below this, waiting costs more responsiveness than it buys. */
  private val MinMs = 60.0

  /** Above this the canvas would feel abandoned, however expensive the render.
    * A slow diagram should lag behind typing, not stop responding to it. */
  private val MaxMs = 900.0

  private var lastRunMs = 0.0

  def delayMs: Int = math.max(MinMs, math.min(MaxMs, lastRunMs)).toInt

  /** Run one pipeline pass and remember what it cost.
    *
    * `setTimeout(0)` rather than timing `run` directly: the pass is synchronous
    * work plus a chain of Future callbacks, and microtasks all drain before a
    * macrotask runs — so this continuation is the first moment the main thread
    * is free again, which is exactly the span a keystroke would have waited on.
    * A genuinely async backend (Mermaid) resolves later than this and is
    * under-measured, which errs toward a SHORTER wait: the failure mode is the
    * old behaviour, not a worse one.
    */
  def timing(run: () => Unit): Unit =
    val t0 = js.Date.now()
    run()
    dom.window.setTimeout(() => lastRunMs = js.Date.now() - t0, 0)

  /** Debounce with a per-event delay. `.debounce(n)` fixes n when the stream is
    * built; a switched delay reads [[delayMs]] on each event, and the switch is
    * what makes it a debounce — a new value cancels the pending one. */
  def pace[A](stream: EventStream[A]): EventStream[A] =
    stream.flatMapSwitch(a => EventStream.delay(delayMs, a))

  /** [[pace]] for a Signal: the current value is kept as-is (a subscriber must
    * always have one), and only the changes are delayed. */
  def paceSignal[A](signal: Signal[A]): Signal[A] =
    signal.composeChanges(pace)
