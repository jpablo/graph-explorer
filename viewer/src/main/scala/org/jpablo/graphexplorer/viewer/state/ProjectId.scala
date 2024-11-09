package org.jpablo.graphexplorer.viewer.state

import upickle.default.*

import scala.scalajs.js

case class ProjectId(value: String) derives ReadWriter

object ProjectId:
  def random =
    ProjectId(js.Dynamic.global.crypto.randomUUID().toString)
