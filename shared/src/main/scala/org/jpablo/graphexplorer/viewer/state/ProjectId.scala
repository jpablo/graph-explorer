package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.utils.Utils.randomUUIDSafe
import upickle.default.*

case class ProjectId(value: String) derives ReadWriter, CanEqual

object ProjectId:
  def random =
    ProjectId(randomUUIDSafe())
