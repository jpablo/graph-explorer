package org.jpablo.graphexplorer.viewer

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.board.{BoardFileView, BoardView, BrowserBoardStore}
import org.jpablo.graphexplorer.gxcore.model.Board
import org.jpablo.graphexplorer.library.{BrowserBoards, Library, LibraryView}
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.backends.DefaultDiagramLanguages
import org.jpablo.graphexplorer.viewer.backends.graphviz.{DotExamples, Graphviz}
import org.jpablo.graphexplorer.viewer.components.{Commands, DiagramBanners, DiagramBody, DiagramView, RouterCommands, Toolbar, TopLevel, TopLevelFrame}
import org.jpablo.graphexplorer.viewer.components.leftPanel.LeftPanel
import org.jpablo.graphexplorer.viewer.desktop.{DesktopDocumentRegistry, DesktopViewWiring}
import org.jpablo.graphexplorer.viewer.logging.Level
import org.jpablo.graphexplorer.viewer.models.ClientSize
import org.jpablo.graphexplorer.viewer.state.{DocumentSessionId, RightPanelSection, ViewerSettings, ViewerState, ViewTarget}
import org.jpablo.graphexplorer.viewer.utils.Platform
import org.jpablo.graphexplorer.viewer.widgets.{Button, primary, small, tiny}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js

/** The services that the pages of the app share. `Viewer.main` makes them one
  * time. A test can make them too, and render the same pages.
  */
final case class AppServices(
    graphviz:       Graphviz,
    router:         Router,
    routerCmds:     RouterCommands,
    errors:         EventBus[String],
    infos:          EventBus[String],
    viewerSettings: Var[ViewerSettings],
    setTheme:       String => Unit,
    clipboardWrite: String => Unit,
    clipboardRead:  () => Future[String],
    clientSize:     ClientSize,
    logLevel:       Level
)

/** What the screen shows for a route. */
private enum Page derives CanEqual:
  /** A diagram of the browser library, or a file that is not a board. One
    * diagram workspace shows all of them.
    */
  case Workspace(diagram: WorkspaceDiagram)
  case Home
  case Example(slug: String)
  /** A file of the desktop that is a board (ADR 0003). */
  case BoardFile(session: DocumentSessionId)
  /** A `/documents/<id>` route whose session the page does not hold. */
  case UnknownFile(sessionId: String)
  case Board(id: String)

  /** The key of the page split. All the workspace pages share one key, so the
    * workspace stays on the screen. Each other page has its own key, so a new
    * route makes its page again, as before.
    */
  def key: Option[Page] =
    this match
      case Workspace(_) => None
      case other        => Some(other)

/** A diagram that the workspace shows. A new value makes a new view.
  *
  * The source of a share link is part of the value. Thus the same id with a
  * different source makes a new view, as before the workspace.
  */
private enum WorkspaceDiagram derives CanEqual:
  case Stored(id: String, source: Option[String])
  case File(session: DocumentSessionId)

/** One visit of a diagram: its state, its commands, and the function that
  * releases them.
  */
private final case class DiagramVisit(state: ViewerState, commands: Commands, close: () => Unit)

/** The parts of the workspace that belong to one diagram. The toolbar is not
  * one of them: the workspace mounts one toolbar, and gives it the `view`.
  */
private final case class DiagramPage(view: DiagramView, banners: Seq[HtmlElement], body: Seq[HtmlElement])

/** The panels that a workspace gives to each of its views. */
private final case class Panels(leftPanelVisible: Var[Boolean], rightPanelActiveSection: Var[RightPanelSection])

/** The page for each route of the app: the library page, a diagram, a file, an
  * example, and a board.
  */
object AppRoutes:

  def apply(services: AppServices): Div =
    import services.*

    /** Make the state of one diagram visit, and link it to the desktop.
      *
      * The panels come from the caller. The workspace gives one Var of each to
      * all its views, so the Library panel and the right panel stay as they
      * are after a switch.
      */
    def openVisit(target: ViewTarget, source: Option[String], panels: Panels): DiagramVisit =
      // An owner for this one diagram visit. The code kills it when the view
      // unmounts. Thus the subscriptions of the ViewerState (phases, persistence,
      // theme, panels...) and the whole object graph that they keep are released.
      // Without it, each navigation leaks one full ViewerState on the window owner,
      // which nothing kills.
      val viewOwner = new ManualOwner
      val state =
        ViewerState(
          target = target,
          graphviz = graphviz,
          writeText = clipboardWrite,
          readText = clipboardRead,
          setTheme = setTheme,
          errorBus = errors,
          infoBus = infos,
          initialSource = source,
          rightPanelActiveSection = panels.rightPanelActiveSection,
          leftPanelVisible = panels.leftPanelVisible,
          clientSize = clientSize,
          logLevel = logLevel
        )(using viewOwner)
      // The leave guard, the close guard, the live view tier and the open
      // handshake, on the owner of this view. The tests call the same
      // function, so they drive this wiring and not a copy.
      val detachDesktop = DesktopViewWiring.attach(state, router)

      // The BROWSER's window closing. A tab has no shell to ask, so the
      // generic prompt is all there is. The desktop does not rely on this:
      // it refuses the close and raises the real three-answer dialog
      // through `DesktopClose`, because §7.4 needs Save, Discard AND
      // Cancel, and a `beforeunload` prompt offers one answer.
      val warnOnClose: js.Function1[dom.Event, Unit] = event =>
        if state.documentIsDirty then
          event.preventDefault()
          event.asInstanceOf[js.Dynamic].updateDynamic("returnValue")("")
      dom.window.addEventListener("beforeunload", warnOnClose)

      DiagramVisit(
        state,
        Commands(state, routerCmds),
        close = () =>
          // The live view tier and the close question both act on "the view
          // now on screen", so they still name one — and it used to outlive
          // the view that set it, so a query after navigation was answered
          // by a viewer nobody was looking at. The document bridge no
          // longer needs one at all: a viewer follows its OWN session.
          detachDesktop()
          dom.window.removeEventListener("beforeunload", warnOnClose)
          state.closePersistence()
          viewOwner.killSubscriptions()
      )

    /** The panels start as the stored viewer settings say. A view sets them
      * again at its restore only when the values are different.
      */
    def storedPanels(): Panels =
      val stored = Library.readViewerSettings()
      Panels(Var(stored.leftPanelVisible), Var(RightPanelSection.fromStored(stored.rightPanelTabIndex)))

    /** The page of a built-in example: a read-only visit with its own Library
      * panel. It turns off persistence and puts the "copy to my library" strip
      * on top. An example is the SAME viewer as a diagram, not a second,
      * weaker one. Each example route builds the whole page again.
      */
    def exampleView(slug: String, name: String, text: String): Div =
      val visit = openVisit(ViewTarget.Example(slug, name), Some(text), storedPanels())
      TopLevel(visit.state, router, visit.commands, Some(exampleBanner(name, visit.state, routerCmds)))
        .amend(onUnmountCallback(_ => visit.close()))

    /** The parts of one diagram in the workspace.
      *
      * A file is the same viewer as a diagram, and a different store (§5, §6).
      * A second, thinner viewer would drift from this one. Only the target
      * differs, and the target is what chooses the store. Each diagram of the
      * desktop opens here (ADR 0001).
      *
      * The text of a file is not passed as `source`. It comes from
      * `FilePersistence.initial`, which reads the registry — so one place
      * holds what the shell sent, and a reload of this route shows the file
      * again rather than an empty diagram.
      */
    def diagramPage(diagram: WorkspaceDiagram, panels: Panels): DiagramPage =
      val visit = diagram match
        case WorkspaceDiagram.Stored(id, source) => openVisit(ViewTarget.diagram(id), source, panels)
        case WorkspaceDiagram.File(session)      => openVisit(ViewTarget.File(session), None, panels)
      DiagramPage(
        DiagramView(visit.state, visit.commands),
        banners = DiagramBanners(visit.state, banner = None),
        // The view dies when its canvas area unmounts. That occurs in the
        // transaction of the switch, as before the workspace.
        body = DiagramBody(visit.state, router, visit.commands, canvasArea = onUnmountCallback(_ => visit.close()))
      )

    /** The page of the diagram routes. It stays on the screen while a person
      * opens one diagram after another. Only the parts of one diagram (the
      * banners, the canvas, the right panel, the dialogs) are built again. The
      * toolbar and the Library panel keep their DOM nodes, and the list keeps
      * its scroll position.
      *
      * The page split makes a new workspace each time that the screen comes to
      * the diagram routes from a different page, and the route split is new on
      * each mount of the app. Thus a workspace mounts one time only, and its
      * diagram split never gives a view whose owner is dead.
      */
    def diagramWorkspace(diagrams: Signal[WorkspaceDiagram]): Div =
      // The workspace owns the panels, so they outlive each view. The toolbar
      // toggles the Library panel (Cmd+B), and each view saves the panels to
      // the settings.
      val panels = storedPanels()
      // One split for all the parts below, so each diagram gets one view.
      val pages: Signal[DiagramPage] =
        diagrams.splitOne(d => d)((diagram, _, _) => diagramPage(diagram, panels))
      val views = pages.map(_.view)
      TopLevelFrame(
        header = Seq(
          Toolbar(views, views.flatMapSwitch(_.state.displayTitle), panels.leftPanelVisible, Platform.isMac),
          // Under the toolbar, and above the canvas row.
          children <-- pages.map(_.banners)
        ),
        leftPanel = LeftPanel(
          views.map(_.state),
          panels.leftPanelVisible.signal,
          DefaultDiagramLanguages(graphviz),
          router,
          routerCmds
        ),
        body = children <-- pages.map(_.body)
      )

    def page(route: Route): Page =
      route match
        case Route.Home                => Page.Home
        case Route.Diagram(id, source) => Page.Workspace(WorkspaceDiagram.Stored(id, source))
        case Route.Example(slug)       => Page.Example(slug)
        case Route.Board(id)           => Page.Board(id)
        case Route.File(sessionId)     =>
          DocumentSessionId.parse(sessionId).flatMap(DesktopDocumentRegistry.get) match
            // A board file is a file with a different page (ADR 0003). The
            // route is the same, so `gx open` and the library open it as a file.
            case Some(open) if Board.isBoardPath(open.path) => Page.BoardFile(open.id)
            case Some(open)                                 => Page.Workspace(WorkspaceDiagram.File(open.id))
            // A bookmark can outlive the session it names, and a person can
            // type this URL. Say so, and offer the way back. Opening an empty
            // viewer here would offer a save with no file to write to.
            case None => Page.UnknownFile(sessionId)

    /** The pages of the routes. Each call makes a new split, with no memory of
      * the pages of an earlier mount.
      */
    def routePages(): Signal[HtmlElement] =
      router.currentRoute
        .map(page)
        .splitOne(_.key): (_, first, pages) =>
          first match
            case Page.Workspace(diagram) =>
              // The split gives this signal only the pages of its key, so the
              // fallback value is never used.
              val diagrams = pages.map:
                case Page.Workspace(d) => d
                case _                 => diagram
              diagramWorkspace(diagrams)

            case Page.Home =>
              LibraryView(graphviz, router, routerCmds, viewerSettings, setTheme)

            case Page.Example(slug) =>
              DotExamples.bySlug.get(slug) match
                case Some((name, example)) =>
                  // The text is fetched HERE rather than at click time, so the
                  // route survives a reload and a pasted link: an example is a
                  // real address, not a transient side effect of a click.
                  div(
                    cls := "contents",
                    child <-- FetchStream
                      .get(example.path)
                      .map(text => exampleView(slug, name, text))
                  )
                case None =>
                  // A renamed or removed example. Say so and offer the way back,
                  // rather than redirecting mid-render (a route change inside the
                  // route signal's own propagation) or drawing an empty viewer
                  // that just looks broken.
                  unknownExample(slug, routerCmds)

            case Page.BoardFile(session) =>
              BoardFileView(session, graphviz, router, errors)

            case Page.UnknownFile(_) =>
              unknownDocumentSession(routerCmds)

            case Page.Board(id) =>
              // A board of the web app. The desktop app keeps a board as a
              // file, on the route of a file.
              if !Library.isDesktop && BrowserBoards.exists(id) then
                BoardView(BrowserBoardStore(id, DefaultDiagramLanguages(graphviz)), graphviz, router, errors)
              else unknownBoard(routerCmds)

    div(onMountInsert(_ => child <-- routePages()))

  /** The strip that makes an ephemeral visit legible: what you are looking at,
    * that it is not yours, and the one click that changes that.
    */
  private def exampleBanner(name: String, state: ViewerState, routerCmds: RouterCommands) =
    div(
      cls := "example-banner",
      span(cls := "example-banner-title", "Example: ", b(name)),
      span(cls := "example-banner-note", "Not saved to your library"),
      Button(
        "Copy to my library",
        // The text as it stands, not the file: whatever the reader tried out
        // while poking at the example comes with them into their copy.
        onClick --> (_ => routerCmds.copyExampleToLibrary.execute(Some((name, state.sourceText.now()))))
      ).primary.tiny
    )

  /** A `/documents/<id>` route whose session the page does not hold. */
  private def unknownDocumentSession(routerCmds: RouterCommands) =
    div(
      cls := "loose-document-placeholder",
      h2("No such document session"),
      p("This page shows a file the desktop opened. That session is not open now."),
      Button(
        "Back to the library",
        onClick --> (_ => routerCmds.navigateHome.execute())
      ).primary.small
    )

  private def unknownBoard(routerCmds: RouterCommands) =
    div(
      cls := "unknown-example",
      h2("No such board"),
      p("This browser has no board with this address."),
      Button(
        "Back to the library",
        onClick --> (_ => routerCmds.navigateHome.execute())
      ).primary.small
    )

  private def unknownExample(slug: String, routerCmds: RouterCommands) =
    div(
      cls := "unknown-example",
      h2("No such example"),
      p("There is no example named ", code(slug), " any more."),
      Button(
        "Back to the library",
        onClick --> (_ => routerCmds.navigateHome.execute())
      ).primary.small
    )
