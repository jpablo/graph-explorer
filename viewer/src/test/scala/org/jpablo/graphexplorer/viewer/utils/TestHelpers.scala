package org.jpablo.graphexplorer.viewer.utils

import munit.{AfterEach, BeforeEach, Fixture}
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.state.TestSetup

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.scalajs.js
import scala.util.Try

trait TestHelpers:

  def withGraphviz(block: Graphviz => Unit)(implicit ec: ExecutionContext): Future[Unit] =
    TestHelpers.sharedGraphviz.map(block)

  def withGraphvizAsync(block: Graphviz => Future[Unit])(implicit ec: ExecutionContext): Future[Unit] =
    TestHelpers.sharedGraphviz.flatMap(block)

  /** Delays execution until the next microtask, allowing already-resolved Futures to propagate.
    *
    * The block is evaluated inside `Try` so an assertion failure FAILS the returned Future (a
    * normal red test). With `p.success(block)` the throw escaped the timer callback as an
    * uncaught window error, killing the whole Playwright JS test run and masking real failures.
    */
  def afterMicrotasks[A](block: => A): Future[A] =
    val p = Promise[A]()
    scala.scalajs.js.timers.setTimeout(0) { p.complete(Try(block)) }
    p.future

  def mockStorageFixture() =
    new Fixture[Unit]("mockStorage"):

      def apply(): Unit = ()

      override def beforeEach(context: BeforeEach): Unit =
        TestSetup.setupMockStorage()

      override def afterEach(context: AfterEach): Unit =
        TestSetup.cleanupMockStorage()

object TestHelpers:

  /** One Graphviz for all the specs of the test run, as the app has one.
    *
    * Each `Graphviz.build()` makes a new viz-js instance, and each instance
    * compiles the Graphviz wasm module again. In a full test run, the specs
    * made many instances. Then the promise of an instance sometimes did not
    * complete, and a test stopped at its timeout of 30 s. `Graphviz` keeps no
    * state of its own, so the specs can share one instance.
    */
  lazy val sharedGraphviz: Future[Graphviz] = Graphviz.build()

/** The timers of the specs that run in the browser.
  *
  * This is a different trait from [[TestHelpers]], because many specs that
  * use [[TestHelpers]] have their own `after` or `waitFor`.
  */
trait TimerHelpers:

  /** The value of `block` after `ms` milliseconds. A failure of `block`
    * fails the future, and not the test run.
    */
  def after[A](ms: Double)(block: => A): Future[A] =
    val p = Promise[A]()
    js.timers.setTimeout(ms)(p.complete(Try(block)))
    p.future

  /** Wait until `condition` is true. The future fails after `timeoutMs`
    * milliseconds, with `description` in its message.
    */
  def waitFor(description: => String, timeoutMs: Int = 15000)(condition: => Boolean): Future[Unit] =
    val p     = Promise[Unit]()
    val start = js.Date.now()
    def loop(): Unit =
      if condition then p.trySuccess(())
      else if js.Date.now() - start >= timeoutMs then
        p.tryFailure(new RuntimeException(s"Timed out after ${timeoutMs}ms. Expected: $description"))
      else js.timers.setTimeout(20)(loop())
    loop()
    p.future
