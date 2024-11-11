package org.jpablo.graphexplorer.router

import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.laminar.api.L.*

enum Route:
  case Home
  case ProjectDetail(uuid: String)

class Router:
  given owner: Owner = OneTimeOwner(() => ())

  // Listen to hashchange events
  windowEvents(_.onHashChange).foreach: event =>
    dom.console.debug("Hash changed", event.newURL)
    // TODO: Consider not doing a full reload everytime the hash changes
    dom.window.location.reload()

  def now(): Route =
    parseHash(dom.window.location.hash)

  def navigateTo(route: Route): Unit =
    dom.window.location.hash = buildHash(route)

  private def parseHash(hash: String): Route =
    val path = hash.stripPrefix("#/").split("/").filter(_.nonEmpty)
    path.toList match
      case projectId :: Nil => Route.ProjectDetail(projectId)
      case _                => Route.Home

  private def buildHash(route: Route): String =
    route match
      case Route.Home              => "#/"
      case Route.ProjectDetail(id) => s"#/$id"
