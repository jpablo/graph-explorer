package org.jpablo.graphexplorer.gxcore.command

import org.jpablo.graphexplorer.viewer.models.{ArrowId, ElementId, GroupId, NodeId}

/** How an element is named when the name has to leave the process.
  *
  * D7.1 says the six `Ops` modules already ARE the vocabulary, and that what
  * they lack is *addressability* — names, serializable argument forms, and
  * stable element references over the wire. This is that last piece.
  *
  * The spelling is the one the model already uses (`node:a`, `arrow:e1`,
  * `group:g1`), not a new scheme: `ElementId.toSvg` has produced it for years,
  * so a reference read off the DOM, out of a command, or out of an audit log is
  * the same string. What did not exist was the inverse for the sum type — each
  * id had its own `fromSvg`, so a caller had to know which kind it wanted before
  * it could parse. A wire reference has to work the other way round: the string
  * says which kind it is.
  *
  * Note what this is NOT: a canvas reference. `ElementId` identifies an element
  * in the MODEL, and one model element can be several drawn things — a Mermaid
  * self-loop is three paths sharing one `ArrowId`. Commands operate on the
  * model, so the model's identity is the right one here; anything keyed by
  * what is drawn needs its own key and must not reuse this.
  */
object ElementRef:

  /** `node:a`, `arrow:e1`, `group:g1`. */
  def render(id: ElementId): String = id.toSvg

  /** Parse a reference, saying what is wrong rather than returning None.
    *
    * A command that names an element the reader cannot resolve is the most
    * common malformed request there is — a typo, a stale id, the wrong kind —
    * and "invalid element reference" without the offending text is not an error
    * message, it is a shrug.
    */
  def parse(text: String): Either[String, ElementId] =
    text.split(":", 2) match
      case Array("node", value) if value.nonEmpty  => Right(NodeId(value))
      case Array("arrow", value) if value.nonEmpty => Right(ArrowId(value))
      case Array("group", value) if value.nonEmpty => Right(GroupId(value))
      case Array(kind, _) if Kinds.contains(kind)  => Left(s"element reference has an empty id: '$text'")
      case Array(kind, _)                          => Left(s"unknown element kind '$kind' in '$text' (expected ${Kinds.mkString(", ")})")
      case _ =>
        Left(s"element reference must be <kind>:<id>, got '$text' (expected ${Kinds.mkString(", ")})")

  private val Kinds = List("node", "arrow", "group")

  /** Parse many, reporting EVERY bad one.
    *
    * Failing on the first is worse than useless for an agent issuing a batch:
    * it fixes one reference, resends, and learns about the next. All of them,
    * once.
    */
  def parseAll(texts: Seq[String]): Either[String, Set[ElementId]] =
    val (bad, good) = texts.map(parse).partitionMap(identity)
    if bad.isEmpty then Right(good.toSet) else Left(bad.mkString("; "))
