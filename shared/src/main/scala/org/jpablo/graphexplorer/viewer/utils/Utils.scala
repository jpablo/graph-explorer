package org.jpablo.graphexplorer.viewer.utils

trait Utils:
  def randomUUID(): String
  def randomUUIDSafe(): String

object Utils extends UtilsPlatform