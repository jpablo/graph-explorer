package org.jpablo.graphexplorer.viewer.utils

import munit.{AfterEach, BeforeEach, Fixture}
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.state.TestSetup

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Try

trait TestHelpers:

  def withGraphviz(block: Graphviz => Unit)(implicit ec: ExecutionContext): Future[Unit] =
    Graphviz.build().map(block)

  def withGraphvizAsync(block: Graphviz => Future[Unit])(implicit ec: ExecutionContext): Future[Unit] =
    Graphviz.build().flatMap(block)

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
