package org.jpablo.graphexplorer.viewer.state

import upickle.default.*
import org.jpablo.graphexplorer.viewer.utils.UuidV4

case class ProjectId(value: String) derives ReadWriter, CanEqual

object ProjectId:
  def random =
    ProjectId(UuidV4())
