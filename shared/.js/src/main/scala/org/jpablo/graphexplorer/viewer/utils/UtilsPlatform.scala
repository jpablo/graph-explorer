package org.jpablo.graphexplorer.viewer.utils

import org.scalajs.dom

import scala.scalajs.js

private[utils] trait UtilsPlatform extends Utils:
  def randomUUID(): String =
    js.Dynamic.global.crypto.randomUUID().toString

  def randomUUIDSafe(): String =
    randomUUID().replace("-", "")

  private var currentVersion: Version = 0

  def nextVersion(): Version =
    dom.console.error(s"nextVersion(): $currentVersion -> ${currentVersion + 1}")
    currentVersion += 1
    currentVersion
