package org.jpablo.graphexplorer.router

import com.raquo.laminar.api.L.*
import Router.diagrams

enum Route derives CanEqual:
  case Home
  case ProjectDetail(uuid: String)

class Router:

  private val currentRouteV = Var(now())

  val currentRoute = currentRouteV.signal.distinct

  private def now(): Route =
    parsePath(dom.window.location.pathname)

  // 1. popstate fires when the user clicks back/forward or we pushState
  windowEvents(_.onPopState)
    .foreach(_ => currentRouteV.set(now()))(unsafeWindowOwner)

  def navigateTo(route: Route): Unit =
    val path = buildPath(route)
    // 2. update the URL bar without reload
    dom.window.history.pushState(null, "", path)
    currentRouteV.set(now())

  private def parsePath(path: String): Route =
    // strip leading slash, split on '/'
    path.stripPrefix("/").split("/").filter(_.nonEmpty).toList match
      case `diagrams` :: id :: Nil => Route.ProjectDetail(id)
      case _                       => Route.Home

  private def buildPath(route: Route): String =
    route match
      case Route.Home              => "/"
      case Route.ProjectDetail(id) => s"/$diagrams/$id"

object Router:
  val diagrams = "diagrams"
