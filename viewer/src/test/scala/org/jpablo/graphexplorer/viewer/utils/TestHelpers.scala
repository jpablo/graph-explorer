package org.jpablo.graphexplorer.viewer.utils

import munit.{AfterEach, BeforeEach, Fixture}
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.state.TestSetup

import scala.concurrent.{ExecutionContext, Future}

trait TestHelpers:

  def withGraphviz(block: Graphviz => Unit)(implicit ec: ExecutionContext): Future[Unit] =
    Graphviz.build().map(block)

  def mockStorageFixture() =
    new Fixture[Unit]("mockStorage"):

      def apply(): Unit = ()

      override def beforeEach(context: BeforeEach): Unit =
        TestSetup.setupMockStorage()

      override def afterEach(context: AfterEach): Unit =
        TestSetup.cleanupMockStorage()
