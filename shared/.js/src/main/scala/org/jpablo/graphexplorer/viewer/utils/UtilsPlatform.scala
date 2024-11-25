package org.jpablo.graphexplorer.viewer.utils

import scala.scalajs.js

private[utils] trait UtilsPlatform extends Utils:
  def randomUUID(): String =
    js.Dynamic.global.crypto.randomUUID().toString

  def randomUUIDSafe(): String =
    randomUUID().replace("-", "")
