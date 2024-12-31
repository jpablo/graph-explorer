package org.jpablo.graphexplorer.viewer.utils


private[utils] trait UtilsPlatform extends Utils:
  def randomUUID(): String =
    UuidV4()

  def randomUUIDSafe(): String =
    randomUUID().replace("-", "")
