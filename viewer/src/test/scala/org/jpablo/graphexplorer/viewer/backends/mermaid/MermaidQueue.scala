package org.jpablo.graphexplorer.viewer.backends.mermaid

import scala.concurrent.{ExecutionContext, Future}

/** Access to the queue of the Mermaid operations, for the tests. */
object MermaidQueue:

  /** Hold the queue until `release` completes. Each parse and each render
    * that comes after this call waits for the release.
    */
  def holdUntil(release: Future[Unit])(using ExecutionContext): Unit =
    MermaidBackend.enqueue(release)
    ()
