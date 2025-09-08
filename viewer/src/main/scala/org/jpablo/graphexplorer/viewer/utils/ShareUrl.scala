package org.jpablo.graphexplorer.viewer.utils

import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.state.ProjectId
import org.scalajs.dom
import scala.scalajs.js

object ShareUrl:
  val param: String = "dot"

  private def encode(value: String): String =
    js.URIUtils.encodeURIComponent(value)

  def buildForProject(projectId: ProjectId, dot: String): String =
    val origin = dom.window.location.origin
    s"$origin/${Router.diagrams}/${projectId.value}?$param=${encode(dot)}"

  def readDotParam(): Option[String] =
    val params = new dom.URLSearchParams(dom.window.location.search)
    Option(params.get(param))
