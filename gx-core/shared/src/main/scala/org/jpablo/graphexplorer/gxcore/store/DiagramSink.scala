package org.jpablo.graphexplorer.gxcore.store

import org.jpablo.graphexplorer.gxcore.model.{Diagram, DiagramId}

/** The slice of a library that a writer needs, named as a capability rather
  * than a place.
  *
  * D7.3 makes the store the live state, which means the SAME migration has to
  * run in two hosts that cannot name each other's types: on the JVM against
  * `LibraryStore` (java.nio), and in the browser against the desktop's library
  * commands (Tauri, no filesystem of its own). Three methods is the whole
  * surface migration touches, so this is the whole trait.
  *
  * The error is a `String` deliberately. A shared trait cannot depend on
  * `StoreError` — that enum names filesystem failures the browser side has no
  * way to produce — and a migration report is read by a person, not branched on.
  */
trait DiagramSink:

  /** Make the library ready to be written to. Idempotent. */
  def initialize(): Unit

  /** Whether a record already exists, which is what makes re-running a
    * migration safe rather than duplicating everything it already imported.
    */
  def contains(id: DiagramId): Boolean

  def write(diagram: Diagram): Either[String, Diagram]
