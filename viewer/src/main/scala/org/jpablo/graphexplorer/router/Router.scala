package org.jpablo.graphexplorer.router

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import org.jpablo.graphexplorer.viewer.utils.ShareUrl
import org.jpablo.graphexplorer.viewer.telemetry.Telemetry
import scala.scalajs.js

import Router.{diagrams, documents, example}

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

  /** One open loose file, named by an opaque session id (§5).
    *
    * The id, and not the path. §13 gives the rule: an absolute path must not go
    * into a URL. The browser keeps a URL in its history, and a path holds
    * private information.
    *
    * A loose file needs a route because a route is a destination. Before this,
    * a loose file had none: `gx open <path>` on Home reached no viewer, and
    * with a project open it put the file text into the viewer of a different
    * record. `DesktopDocumentRegistry` maps the id back to the file.
    */
  case LooseDocument(sessionId: String)

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
    * A loose file with an unsaved edit uses it: leaving would discard the edit,
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
    if leaveGuard.forall(_(route)) then forceNavigateTo(route)

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
      case `diagrams` :: id :: Nil       => Route.ProjectDetail(id, sourceOpt)
      case `example` :: slug :: Nil      => Route.Example(slug)
      // The id is not checked here. The router does not read the registry, in
      // the same way that it does not read the library for `diagrams`. The view
      // holds that check, because only the view can show the answer.
      case `documents` :: session :: Nil => Route.LooseDocument(session)
      case _                             => Route.Home

  private def buildPath(route: Route): String =
    route match
      case Route.Home                     => "/"
      case Route.ProjectDetail(id, _)     => s"/$diagrams/$id"
      case Route.Example(slug)            => s"/$example/$slug"
      case Route.LooseDocument(sessionId) => s"/$documents/$sessionId"

object Router:
  val diagrams = "diagrams"

  /** Singular on purpose: the example FILES are served from `/examples/`, and a
    * route sharing that prefix would race the static handler.
    */
  val example = "example"

  /** Plural, and matching no static directory. §5 gives the shape
    * `/documents/<opaque-session-id>`.
    */
  val documents = "documents"
