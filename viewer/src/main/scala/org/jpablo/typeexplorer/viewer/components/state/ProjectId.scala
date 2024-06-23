package org.jpablo.typeexplorer.viewer.components.state

import zio.json.*

case class ProjectId(value: String) extends AnyVal

object ProjectId:
  given JsonCodec[ProjectId] =
    JsonCodec(
      JsonEncoder.string.contramap(_.value),
      JsonDecoder.string.map(ProjectId(_))
    )
  given JsonFieldEncoder[ProjectId] =
    JsonFieldEncoder.string.contramap(_.value)
  given JsonFieldDecoder[ProjectId] =
    JsonFieldDecoder.string.map(ProjectId(_))
