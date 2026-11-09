package org.jpablo.graphexplorer.viewer.utils

import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.desktop.DesktopIpc
import org.jpablo.graphexplorer.viewer.models.DiagramId
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
  def buildForDiagram(diagramId: DiagramId, dot: String): String =
    val origin = if DesktopIpc.available then publicOrigin else dom.window.location.origin
    s"$origin/${Router.diagrams}/${diagramId.value}?$param=${encode(dot)}"

  def readDotParam(): Option[String] =
    val params = new dom.URLSearchParams(dom.window.location.search)
    Option(params.get(param))

  /** The diagram id in the path of a share URL (`/diagrams/<id>?dot=...`), if the path has one. */
  def readDiagramIdFromPath(): Option[DiagramId] =
    dom.window.location.pathname.split("/").toList.filter(_.nonEmpty) match
      case seg :: id :: Nil if seg == Router.diagrams => Some(DiagramId(id))
      case _                                          => None
