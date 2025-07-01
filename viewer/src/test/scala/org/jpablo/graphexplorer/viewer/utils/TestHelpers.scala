package org.jpablo.graphexplorer.viewer.utils

import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz

import scala.concurrent.{ExecutionContext, Future}

trait TestHelpers:

  def withGraphviz(block: Graphviz => Unit)(implicit ec: ExecutionContext): Future[Unit] =
    Graphviz.build().map(block)
