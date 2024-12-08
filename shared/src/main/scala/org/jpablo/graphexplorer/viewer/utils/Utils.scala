package org.jpablo.graphexplorer.viewer.utils

type Version = Long

trait Utils:
  def randomUUID(): String
  def randomUUIDSafe(): String

  def nextVersion(): Version

object Utils extends UtilsPlatform
