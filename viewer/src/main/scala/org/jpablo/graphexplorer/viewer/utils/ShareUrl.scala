package org.jpablo.graphexplorer.viewer.utils

import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.desktop.DesktopIpc
import org.jpablo.graphexplorer.viewer.state.ProjectId
import org.scalajs.dom
import scala.scalajs.js

object ShareUrl:
  val param: String = "dot"

  /** The public web app. The desktop app serves the page from a webview origin
    * (`tauri://localhost`, or `http://tauri.localhost` on Windows). Only this
    * computer can open that origin, so a desktop share link uses this address.
    */
  val publicOrigin: String = "https://graph-explorer.net"

  private def encode(value: String): String =
    js.URIUtils.encodeURIComponent(value)

  /** The link that a user copies to share a diagram. On the web, the link keeps
    * the origin of the page, so local dev and previews link to themselves.
    */
  def buildForProject(projectId: ProjectId, dot: String): String =
    val origin = if DesktopIpc.available then publicOrigin else dom.window.location.origin
    s"$origin/${Router.diagrams}/${projectId.value}?$param=${encode(dot)}"

  def readDotParam(): Option[String] =
    val params = new dom.URLSearchParams(dom.window.location.search)
    Option(params.get(param))

  /** The project id embedded in a share-URL path (`/diagrams/<id>?dot=...`), if any. */
  def readProjectIdFromPath(): Option[ProjectId] =
    dom.window.location.pathname.split("/").toList.filter(_.nonEmpty) match
      case seg :: id :: Nil if seg == Router.diagrams => Some(ProjectId(id))
      case _                                          => None
