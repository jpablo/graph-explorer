package org.jpablo.typeexplorer.viewer.utils

import scala.scalajs.js

object Utils:
  def randomUUID(): String =
    js.Dynamic.global.crypto.randomUUID().toString
