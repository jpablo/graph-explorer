package org.jpablo.graphexplorer.viewer.utils

type Version = Long

trait Utils:
  def randomUUID(): String
  def randomUUIDSafe(): String

object Utils extends UtilsPlatform

enum ChangeOrigin:
  case CodeMirror, Graph
