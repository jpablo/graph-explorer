package org.jpablo.graphexplorer.viewer.utils

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("./node_modules/uuid", "v4")
object UuidV4 extends js.Object:
  def apply(): String = js.native

