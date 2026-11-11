package org.jpablo.graphexplorer.viewer.utils

import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** Waits for the specs that run in the browser. */
object Waits:

  /** A future that completes after `ms` milliseconds. */
  def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  /** Wait until `condition` is true, or until the time ends. The future does
    * not fail at the end of the time. The checks that come after it tell what
    * is wrong.
    */
  def settle(condition: => Boolean, timeoutMs: Int = 5000): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition || js.Date.now() - start >= timeoutMs then p.trySuccess(())
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future
