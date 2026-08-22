package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe

/** The name of one open loose file (§5 of
  * docs/desktop-open-targets-and-persistence.md).
  *
  * The route carries this id. The route does not carry the path. §13 gives the
  * rule: an absolute path must not go into a URL, into telemetry, or into a
  * routine log. A path holds private information, and the browser keeps the URL
  * in its history.
  *
  * The id is random. The id is not a hash of the path. A hash is stable, but a
  * hash also lets a reader who has a candidate path confirm that path from the
  * URL. `DesktopDocumentRegistry` returns the same id for the same path, so
  * the registry gives the stability that a hash would give, and gives nothing
  * else away.
  *
  * The prefix marks the id. A route holds a [[ProjectId]] value and a session
  * id as the same type, `String`. The prefix makes a confusion between the two
  * visible instead of silent.
  *
  * Beside [[ProjectId]] rather than in the desktop package, because this is the
  * other half of [[ViewTarget]]: one identity for a library record, one for a
  * loose file. `DesktopDocumentRegistry` stays in the desktop package, because
  * only the shell can fill it.
  */
case class DocumentSessionId(value: String) derives CanEqual

object DocumentSessionId:

  private val Prefix = "doc-"

  def random: DocumentSessionId =
    DocumentSessionId(Prefix + randomUUIDSafe())

  /** Read an id from a route, or from any other text the page does not control.
    *
    * The result is None if the text is not an id. A URL is user input: a person
    * can type `/documents/anything`, and a bookmark can outlive the session it
    * names. The caller gets an Option, and must show something for a session
    * that is not open.
    */
  def parse(text: String): Option[DocumentSessionId] =
    val trimmed = text.trim
    Option.when(trimmed.startsWith(Prefix) && trimmed.length > Prefix.length):
      DocumentSessionId(trimmed)
