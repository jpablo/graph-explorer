package org.jpablo.graphexplorer.router

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.utils.ShareUrl
import org.jpablo.graphexplorer.viewer.telemetry.Telemetry
import scala.scalajs.js

import Router.{diagrams, example}

enum Route derives CanEqual:
  case Home
  case ProjectDetail(uuid: String, source: Option[String] = None)

  /** A built-in example, opened WITHOUT adding it to the library.
    *
    * Its own route rather than a flag on ProjectDetail: an example has no
    * ProjectId to name, and the distinction has to survive a reload and the back
    * button — clicking an example used to mint a library copy purely as a way to
    * have something to navigate to.
    */
  case Example(slug: String)

class Router:
  given Owner = unsafeWindowOwner

  private val currentRouteV = Var(now())

  val currentRoute = currentRouteV.signal.distinct

  private def now(): Route =
    parsePath(currentPathname())

  // --- Environment helpers (SSR / Node-friendly) ---
  private inline def hasWindow: Boolean = js.typeOf(js.Dynamic.global.selectDynamic("window")) != "undefined"

  private def currentPathname(): String =
    if hasWindow then dom.window.location.pathname else "/"

  private def currentSearch(): String =
    if hasWindow then dom.window.location.search else ""

  // 📊 Hook GA after the router is initialized:
  currentRoute.foreach: route =>
    val path = buildPath(route)
    Telemetry.log("router.routeChanged", "path" -> path, "route" -> route.toString)
    // call the gtag function if available (avoid ReferenceError in tests / SSR)
    try
      val gtag = js.Dynamic.global.selectDynamic("gtag")
      if js.typeOf(gtag) == "function" then
        gtag("event", "page_view", js.Dictionary("page_path" -> path))
    catch case _: Throwable => () // ignore in test/SSR environments

  // 1. popstate fires when the user clicks back/forward or we pushState
  if hasWindow && js.typeOf(js.Dynamic.global.window.selectDynamic("addEventListener")) == "function" then
    windowEvents(_.onPopState)
      .foreach(_ => currentRouteV.set(now()))

  def navigateTo(route: Route): Unit =
    val path = buildPath(route)
    Telemetry.markNavigationStart(path)
    // 2. update the URL bar without reload
    if hasWindow && js.typeOf(js.Dynamic.global.window.selectDynamic("history")) != "undefined" &&
      js.typeOf(js.Dynamic.global.window.history.selectDynamic("pushState")) == "function"
    then
      dom.window.history.pushState(null, "", path)
    currentRouteV.set(route)

  private def parsePath(path: String): Route =
    // strip leading slash, split on '/'
    val sourceOpt = if hasWindow then ShareUrl.readDotParam() else None

    path.stripPrefix("/").split("/").filter(_.nonEmpty).toList match
      case `diagrams` :: id :: Nil  => Route.ProjectDetail(id, sourceOpt)
      case `example` :: slug :: Nil => Route.Example(slug)
      case _                        => Route.Home

  private def buildPath(route: Route): String =
    route match
      case Route.Home                 => "/"
      case Route.ProjectDetail(id, _) => s"/$diagrams/$id"
      case Route.Example(slug)        => s"/$example/$slug"

object Router:
  val diagrams = "diagrams"

  /** Singular on purpose: the example FILES are served from `/examples/`, and a
    * route sharing that prefix would race the static handler.
    */
  val example = "example"
