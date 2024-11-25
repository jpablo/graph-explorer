package org.jpablo.graphexplorer.viewer.utils

import java.util.UUID

private[utils] trait UtilsPlatform extends Utils:
  def randomUUID(): String =
    UUID.randomUUID().toString

  def randomUUIDSafe(): String =
    randomUUID().replace("-", "")
