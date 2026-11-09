package org.jpablo.graphexplorer.router

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.utils.ShareUrl
import org.jpablo.graphexplorer.viewer.telemetry.Telemetry
import scala.scalajs.js

import Router.{app, diagrams, documents, example}

enum Route derives CanEqual:
  case Home
  /** A diagram of the browser library. Only the web app has this route. */
  case Diagram(id: String, source: Option[String] = None)

  /** A built-in example that opens WITHOUT a copy in the library.
    *
    * It has its own route, and not a flag on [[Diagram]]. An example has no
    * DiagramId, and the difference must stay after a reload and the back
    * button. Before, a click on an example made a copy in the library, only to
    * have a destination for the navigation.
    */
  case Example(slug: String)

  /** One open file, named by an opaque session id (§5). The desktop app opens
    * each diagram on this route, because each diagram there is a file.
    *
    * The id, and not the path. §13 gives the rule: an absolute path must not go
    * into a URL. The browser keeps a URL in its history, and a path holds
    * private information.
    *
    * A file needs a route, because a route is a destination. Before this, a
    * file had no route. On Home, `gx open <path>` reached no viewer. With a
    * diagram open, it put the file text into the viewer of a different
    * diagram. `DesktopDocumentRegistry` maps the id back to the file.
    */
  case File(sessionId: String)

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

  /** A view can refuse to be left (§7.4).
    *
    * One guard, registered by the mounted view and cleared when it unmounts.
    * A file with an unsaved edit uses it: leaving would discard the edit,
    * and §7.4 requires the person to choose first.
    */
  private var leaveGuard: Option[Route => Boolean] = None

  def guardNavigation(guard: Route => Boolean): Unit = leaveGuard = Some(guard)

  /** Release a guard whose view is going away.
    *
    * The identity check is not padding. Laminar mounts the incoming view BEFORE
    * unmounting the outgoing one, so the old view's teardown arrives after the
    * new one has registered. Clearing unconditionally would drop the LIVE
    * guard, and the next unsaved file would be left without a question.
    */
  def clearNavigationGuard(guard: Route => Boolean): Unit =
    if leaveGuard.exists(_ eq guard) then leaveGuard = None

  // 1. popstate fires when the user clicks back/forward or we pushState
  if hasWindow && js.typeOf(js.Dynamic.global.window.selectDynamic("addEventListener")) == "function" then
    windowEvents(_.onPopState)
      .foreach: _ =>
        val next = now()
        if leaveGuard.forall(_(next)) then currentRouteV.set(next)
        else
          // The URL already moved: `popstate` reports a navigation that has
          // happened. Putting the old path back is what makes the refusal
          // visible, and it leaves the guard's dialog looking at the page the
          // person is still on.
          if js.typeOf(js.Dynamic.global.window.selectDynamic("history")) != "undefined" then
            dom.window.history.pushState(null, "", buildPath(currentRouteV.now()))

  /** Navigate, unless the mounted view refuses. */
  def navigateTo(route: Route): Unit =
    tryNavigateTo(route)
    ()

  /** Navigate and report whether the mounted view accepted the request. */
  def tryNavigateTo(route: Route): Boolean =
    if leaveGuard.forall(_(route)) then
      forceNavigateTo(route)
      true
    else false

  /** Navigate whatever the guard says.
    *
    * The guard's own dialog calls this after the person chooses, and it must
    * not be asked the same question twice.
    */
  def forceNavigateTo(route: Route): Unit =
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
      case `app` :: Nil                  => Route.Home
      case `diagrams` :: id :: Nil       => Route.Diagram(id, sourceOpt)
      case `example` :: slug :: Nil      => Route.Example(slug)
      // The id is not checked here. The router does not read the registry, in
      // the same way that it does not read the library for `diagrams`. The view
      // holds that check, because only the view can show the answer.
      case `documents` :: session :: Nil => Route.File(session)
      case _                             => Route.Home

  private def buildPath(route: Route): String =
    route match
      case Route.Home            => Router.homePath
      case Route.Diagram(id, _)  => s"/$diagrams/$id"
      case Route.Example(slug)   => s"/$example/$slug"
      case Route.File(sessionId) => s"/$documents/$sessionId"

object Router:
  /** The library. On the web, `/` is the product page, which Netlify serves
    * from `site/index.html` (see `_redirects`). A library at `/` would show the
    * product page on a reload. The desktop loads `/`, and `/` still falls
    * through to Home.
    */
  val app = "app"

  val homePath = s"/$app"

  val diagrams = "diagrams"

  /** Singular on purpose: the example FILES are served from `/examples/`, and a
    * route sharing that prefix would race the static handler.
    */
  val example = "example"

  /** Plural, and matching no static directory. §5 gives the shape
    * `/documents/<opaque-session-id>`.
    */
  val documents = "documents"
