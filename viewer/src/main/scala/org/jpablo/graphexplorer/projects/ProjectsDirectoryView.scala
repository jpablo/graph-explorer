package org.jpablo.graphexplorer.projects

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.components.{AboutDialog, PreferencesDialog, RouterCommands}
import org.jpablo.graphexplorer.viewer.state.ViewerSettings
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import org.jpablo.graphexplorer.viewer.widgets.{Button, Dropdown, IconButton, MenuEntry, primary, small}
import org.jpablo.graphexplorer.viewer.widgets.MenuEntry.{MenuOption, Sep}
import org.jpablo.graphexplorer.viewer.widgets.{FilterChips, IconRadioGroup, InputBox, InputVariant, SelectBox, SelectVariant, TooltipPos}
import org.jpablo.graphexplorer.viewer.backends.{DefaultDiagramLanguages, DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.DotExamples
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.state.{ProjectId, ThumbnailRenderer}
import org.jpablo.graphexplorer.viewer.state.PersistedDiagramState.minimalGraphText
import org.jpablo.graphexplorer.viewer.telemetry.Telemetry

import scala.scalajs.js
import scala.concurrent.ExecutionContext.Implicits.global

enum SortOption derives CanEqual:
  case LastModified, Title, CreationDate

  def label: String = this match
    case LastModified => "Last Modified"
    case Title        => "Title"
    case CreationDate => "Creation Date"

object SortOption:
  /** Newest first, so a diagram you just made is at the top of the library. */
  val default = CreationDate

  /** Reads a persisted name, tolerating one this build no longer has. `valueOf`
    * would throw, and the caller is `ViewerSettings` — where one bad field costs
    * the user every OTHER setting, since a failed parse falls back to `empty`
    * and the sync writes that back.
    */
  def parse(stored: Option[String]): SortOption =
    stored.flatMap(name => values.find(_.toString == name)).getOrElse(default)

/** How the library section draws its projects: thumbnail cards for browsing by
  * shape, compact rows for scanning by name and date. Persisted as
  * `ViewerSettings.libraryListMode`, so it names the two renderers rather than
  * abstract "modes".
  */
enum LibraryViewMode derives CanEqual:
  case Cards, Rows

/** Badge marking a card's diagram kind, so DOT and Mermaid documents are
  * distinguishable at a glance in the library and the examples gallery.
  * Text comes from the format itself; only the accent color is per-format,
  * with a neutral default so a new backend is badged without editing here.
  */
private val badgeColorByFormat = Map[DiagramFormat, String](
  DiagramFormat.DOT     -> "badge-neutral",
  DiagramFormat.Mermaid -> "badge-secondary"
)

private def formatBadge(format: DiagramFormat) =
  val color = badgeColorByFormat.getOrElse(format, "badge-neutral")
  span(cls := s"badge badge-xs $color badge-outline shrink-0", format.toString)

/** The diagram's kind beside its format: `digraph`/`graph` for DOT, the header
  * keyword (flowchart, sequence, ...) for Mermaid. Ghost, not outline — it
  * qualifies the format badge rather than competing with it.
  */
private def kindBadge(info: ProjectCardInfo) =
  info.diagramKind.map(kind => span(cls := "badge badge-xs badge-ghost shrink-0", kind))

def ProjectsDirectoryView(
    graphviz:       Graphviz,
    router:         Router,
    routerCmds:     RouterCommands,
    viewerSettings: Var[ViewerSettings],
    setTheme:       String => Unit
) =
  // Sort and kind filter are zooms over the settings Var, like the view mode
  // below: how you left the library is how you find it. Deliberately NOT the
  // search term — a persisted query would greet you with a library that looks
  // half-empty for a reason scrolled off the screen.
  val sortOptionVar: Var[SortOption] =
    viewerSettings.zoomLazy(s => SortOption.parse(s.librarySort))((s, o) => s.copy(librarySort = Some(o.toString)))
  val searchTermVar = Var("")
  // None = all kinds. Enumerated from DiagramFormat.values, so a new backend is
  // filterable without touching this component; stored by name for the same
  // reason (see ViewerSettings.libraryFormatFilter).
  val kindFilterVar: Var[Option[DiagramFormat]] =
    viewerSettings.zoomLazy(s => s.libraryFormatFilter.flatMap(name => DiagramFormat.values.find(_.toString == name)))(
      (s, f) => s.copy(libraryFormatFilter = f.map(_.toString))
    )
  val aboutDialogOpen       = Var(false)
  val preferencesDialogOpen = Var(false)
  // A zoom over the settings Var rather than a separate Var: the mount-time
  // settings refresh below and the persistence sync both see it for free.
  val viewModeVar: Var[LibraryViewMode] =
    viewerSettings.zoomLazy(s => if s.libraryListMode then LibraryViewMode.Rows else LibraryViewMode.Cards)((s, m) =>
      s.copy(libraryListMode = m == LibraryViewMode.Rows)
    )
  // Same zoom trick for the one editing preference the dialog carries. It only
  // takes effect on the detail page, but a preference the user can see in one
  // place and not the other is the inconsistency this whole change is about.
  val promptLabelVar: Var[Boolean] =
    viewerSettings.zoomLazy(_.promptLabelBeforeNewNode)((s, b) => s.copy(promptLabelBeforeNewNode = b))
  val enable3DVar: Var[Boolean] =
    viewerSettings.zoomLazy(_.enable3D)((s, b) => s.copy(enable3D = b))

  // The detail toolbar's gear menu minus the entries that need a diagram open
  // (keyboard shortcuts). Same labels and order as Toolbar.gearMenu.
  val gearMenu: Signal[Seq[MenuEntry[() => Unit]]] =
    Signal.fromValue(
      Seq(
        MenuOption("Preferences…", () => preferencesDialogOpen.set(true), Some("Theme and editing preferences"), None),
        Sep,
        MenuOption("About Graph Explorer", () => aboutDialogOpen.set(true), None, None),
        MenuOption(
          "Source on GitHub",
          () => { dom.window.open("https://github.com/jpablo/graph-explorer/tree/viewer", "_blank"); () },
          None,
          None
        )
      )
    )

  div(
    idAttr := "projects-view",
    onMountCallback: _ =>
      // The detail page persists settings through its own Var, so the one we
      // were handed can be stale by now (e.g. a theme picked while viewing a
      // diagram). Refresh from storage so the selector shows the truth and a
      // write from here cannot clobber other settings with stale values.
      viewerSettings.set(ProjectStorage.readViewerSettings())
      val t0 = Telemetry.nowMs()
      val navDtMs = Telemetry.consumeNavigationStartMs("/")
      Telemetry.log(
        "home.mount",
        "dtSinceNavMs" -> navDtMs.getOrElse(-1.0)
      )
      dom.window.requestAnimationFrame(_ => Telemetry.log("home.raf1", "dtSinceMountMs" -> (Telemetry.nowMs() - t0)))
    ,
    onUnmountCallback: _ =>
      Telemetry.log("home.unmount"),
    div(
      cls := "navbar bg-base-100",
      div(
        cls := "flex-1 flex items-center gap-2 ml-2",
        img(src := "/favicon.svg", cls := "h-6 w-6"),
        // The app's name, not a control: it carried `btn btn-ghost` and lit up on hover
        // while having no href and no click handler — an affordance promising nothing.
        span(cls := "text-xl font-semibold", "Graph Explorer")
      ),
      div(
        cls := "flex-none mr-2 flex items-center gap-2",
        // The detail toolbar's gear, mirrored here — same entries, same order.
        // Previously this was two loose icons plus a theme select sitting in the
        // bar, which is the arrangement the detail page had already abandoned
        // ("one gear instead of five loose icons + a theme select", Toolbar):
        // the theme was a page-level control here and a preference there, so the
        // same setting lived in two unrelated places. Behind the gear on both.
        Dropdown(
          // The trigger is a bare glyph, so without this it announces as
          // "button" — and the two controls it replaced here (info, source) both
          // had names. Same label on the detail toolbar's gear.
          title = aria.label := "Settings",
          options = gearMenu,
          onClickHandler = _ --> (action => action()),
          icon = i(cls := "bi bi-gear"),
          menuCls = "w-56"
        ).amend(cls := "dropdown-end")
      )
    ),
    AboutDialog(aboutDialogOpen),
    PreferencesDialog(
      open = preferencesDialogOpen,
      currentTheme = viewerSettings.signal.map(_.currentTheme),
      onSelectTheme = theme =>
        viewerSettings.update(_.copy(currentTheme = Some(theme)))
        setTheme(theme)
      ,
      promptLabelBeforeNewNode = promptLabelVar,
      enable3D = enable3DVar
    ),
    div(
      idAttr := "projects-body",
      // Projects navbar with background
      div(
        cls := "navbar px-6",
        // left side
        div(
          cls := "flex-1",
          h1(
            cls := "text-2xl font-bold gap-2 flex",
            span().folderIcon,
            "Library"
          )
        ),
        // right side
        div(
          cls := "flex flex-wrap md:flex-nowrap justify-end items-center gap-2", // Wrap on small, no-wrap on medium+
          // Search input
          InputBox(
            InputVariant.sm,
            i(cls := "bi bi-search"),
            input(
              cls         := "grow",
              tpe         := "search",
              placeholder := "Search library...",
              controlled(
                value <-- searchTermVar,
                onInput.mapToValue --> searchTermVar
              )
            )
          ),
          // Kind filter: one chip per format, one click to filter, the choice
          // stays visible; the widget's reset button restores All kinds.
          FilterChips(
            groupName  = "library-kind-filter",
            options    = DiagramFormat.values.toSeq,
            labelOf    = _.toString,
            selected   = kindFilterVar,
            resetTitle = "All kinds"
          ),
          // Sort dropdown
          SelectBox(
            SelectVariant.sm,
            cls := "h-8",
            SortOption.values.toSeq.map { opt =>
              option(
                value := opt.toString,
                opt.label
              )
            },
            value <-- sortOptionVar.signal.map(_.toString),
            onChange.mapToValue.map(SortOption.valueOf) --> sortOptionVar
          ),
          // Cards ⇄ rows, next to the sort it complements: rows are the mode
          // where sorting by date actually reads as a sorted column.
          IconRadioGroup(
            Seq(
              (LibraryViewMode.Cards, "bi bi-grid", "View as cards"),
              (LibraryViewMode.Rows, "bi bi-list-ul", "View as list")
            ),
            viewModeVar
          ),
          Button(
            cls := "h-8",
            span().plusCircleIcon,
            "Create Diagram",
            onClick --> routerCmds.createProject.execute(Some(Some(minimalGraphText)))
          ).primary.small
        )
      ),

      // Projects grid with search filter
      div(
        idAttr := "projects-grid",
        cls("list-mode") <-- viewModeVar.signal.map(_ == LibraryViewMode.Rows),
        children <-- {
          val debouncedSearch = searchTermVar.signal.changes
            .debounce(300)
            .startWith("")

          val languages = DefaultDiagramLanguages(graphviz)

          // Keyed on the directory alone: each entry is a synchronous localStorage read
          // plus a full-document JSON parse, so it must NOT sit inside the search/sort/
          // filter combine below (every debounce tick would rescan the whole library).
          // Thumbnails stay lazy behind the IntersectionObserver regardless.
          val cardInfoSignal: Signal[Map[ProjectId, ProjectCardInfo]] =
            ProjectStorage.directory.map: dir =>
              dir.projects.flatMap(p => ProjectStorage.projectCardInfo(p.id, languages).map(p.id -> _)).toMap

          ProjectStorage.directory
            .combineWithFn(debouncedSearch, sortOptionVar.signal, kindFilterVar.signal, cardInfoSignal, viewModeVar.signal):
              (directory, searchTerm, sortOption, kindFilter, infos, viewMode) =>
                Telemetry.time(
                  "home.projects.computeCards",
                  "projectsTotal" -> directory.projects.size,
                  "searchLen"     -> searchTerm.length,
                  "sort"          -> sortOption.toString,
                  "kind"          -> kindFilter.fold("AllKinds")(_.toString),
                  "view"          -> viewMode.toString
                ):
                  // Search and Title-sort see what the card shows (the diagram's title for
                  // never-renamed projects), but the stored name keeps matching too.
                  def shownName(p: ProjectInfo) = infos.get(p.id).map(_.displayName).getOrElse(p.name)
                  val filteredProjects = directory.projects
                    .filter(p => s"${p.name} ${shownName(p)}".toLowerCase.contains(searchTerm.toLowerCase))
                    .filter(p => kindFilter.forall(k => infos.get(p.id).exists(_.format == k)))
                  val sorted =
                    sortOption match
                      case SortOption.LastModified => filteredProjects.sortBy(-_.lastModified)
                      case SortOption.Title        => filteredProjects.sortBy(shownName(_).toLowerCase)
                      case SortOption.CreationDate => filteredProjects.sortBy(-_.createdAt)
                  Telemetry.log("home.projects.cardsReady", "projectsShown" -> sorted.size)
                  val render = viewMode match
                    case LibraryViewMode.Cards => projectCard(graphviz, router, _, _)
                    case LibraryViewMode.Rows  => projectRow(graphviz, router, _, _)
                  sorted.map(p => render(infos.get(p.id), p))
        }
      ),

      // -------- Examples Section --------
      div(
        idAttr := "examples-grid",
        // Title
        h1(
          cls := "text-2xl font-bold gap-2 flex mb-4",
          span().folderIcon,
          "Examples"
        ),
        // Examples grid
        div(
          cls := "grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 gap-4",
          children <-- Signal.fromValue(
            DotExamples.examples
              .filterNot { case (_, example) =>
                example.path == DotExamples.emptyGraph.path || example.path == DotExamples.emptyMermaidGraph.path
              }
              .toSeq
              .map { case (name, example) =>
                exampleCard(graphviz, routerCmds, name, example)
              }
          )
        )
      )
    )
  )

/** Mount hooks that call `enable` the first time the element scrolls into view,
  * then disconnect. Falls back to eager when IntersectionObserver is absent.
  * Every thumbnail shell on this page mounts these, so the library only renders
  * previews the user actually scrolls to.
  */
private def enableWhenFirstVisible(enable: () => Unit): Seq[Modifier[HtmlElement]] =
  var observerOpt: Option[js.Dynamic] = None
  Seq(
    onMountCallback: ctx =>
      val ioCtor = js.Dynamic.global.selectDynamic("IntersectionObserver")
      if js.typeOf(ioCtor) == "function" then
        var obs: js.Dynamic = null
        obs = js.Dynamic.newInstance(ioCtor)(
          (entries: js.Array[js.Dynamic], _: js.Dynamic) =>
            val entry0 = entries(0)
            if entry0 != null && entry0.selectDynamic("isIntersecting").asInstanceOf[Boolean] then
              enable()
              if obs != null then obs.disconnect()
        )
        obs.observe(ctx.thisNode.ref)
        observerOpt = Some(obs)
      else
        enable()
    ,
    onUnmountCallback: _ =>
      observerOpt.foreach(_.disconnect())
      observerOpt = None
  )

/** Clicking an example OPENS it; it lands in the library only if the reader asks
  * for it from the banner there. It used to create a copy on the way in, so
  * browsing the gallery silently filled the library with diagrams nobody chose.
  */
private val openExampleTitle = "Open this example (it is not added to your library)"

private def exampleCard(
    graphviz:   Graphviz,
    routerCmds: RouterCommands,
    name:       String,
    example:    DotExamples.ExampleSource
) = {

  val previewEnabled = Var(false)

  def enablePreview(): Unit =
    if !previewEnabled.now() then
      Telemetry.log("home.exampleThumb.visible", "example" -> name, "path" -> example.path)
      previewEnabled.set(true)

  div(
    cls := "example-card card card-sm", // v5: card-compact became card-sm
    figure(
      div(
        cls := "w-full h-32 overflow-hidden bg-base-200 flex items-center justify-center cursor-pointer",
        enableWhenFirstVisible(() => enablePreview()),
        // --- Generate SVG preview (lazy) ---
        child <-- previewEnabled.signal.map:
          case false =>
            div(
              cls := "w-full h-full p-1 flex items-center justify-center text-base-content/40 text-xs",
              "Loading preview…",
              title := openExampleTitle,
              onClick --> routerCmds.openExample.execute(Some(DotExamples.slugFor(name)))
            )
          case true =>
            div(
              cls := "w-full h-full",
              child <--
                FetchStream
                  .get(example.path)
                  .flatMapSwitch: str =>
                    Telemetry.log("home.exampleThumb.start", "example" -> name, "path" -> example.path, "sourceChars" -> str.length)
                    ThumbnailRenderer
                      .processDotText(
                        graphviz,
                        DotText(str),
                        telemetryContext = Seq(
                          "example" -> name,
                          "path"    -> example.path
                        )
                      )
                  .map: svgElement =>
                    div(
                      cls := "w-full h-full p-1 flex items-center justify-center",
                      div(
                        cls := "w-full h-full relative",
                        div(
                          cls := "absolute inset-0 w-full h-full",
                          svgElement.amend(
                            svg.width               := "100%",
                            svg.height              := "100%",
                            svg.preserveAspectRatio := "xMidYMid meet"
                          )
                        )
                      ),
                      title := openExampleTitle,
                      onClick --> routerCmds.openExample.execute(Some(DotExamples.slugFor(name)))
                    )
            )
      )
    ),
    div(
      cls := "card-body p-2",
      h2(
        cls := "card-title text-sm justify-center gap-2",
        name,
        formatBadge(example.format)
      )
    )
  )
}

/** The project's rendered thumbnail: content read, rendered, and wrapped to fill
  * whatever box the caller puts it in. Shared by the card and the list row, so
  * the two modes cannot drift in how a diagram is previewed.
  */
private def projectThumbnail(graphviz: Graphviz, project: ProjectInfo): Signal[Div] =
  ProjectStorage.getProjectContent(project.id).distinct
    .flatMapSwitch: str =>
      Telemetry.log(
        "home.thumb.start",
        "projectId"   -> project.id.value,
        "name"        -> project.name,
        "sourceChars" -> str.length
      )
      ThumbnailRenderer.processDotText(
        graphviz,
        DotText(str),
        telemetryContext = Seq(
          "projectId" -> project.id.value,
          "name"      -> project.name
        )
      )
    .map: svgElement =>
      div(
        cls := "w-full h-full p-1 flex items-center justify-center",
        div(
          cls := "w-full h-full relative",
          div(
            cls := "absolute inset-0 w-full h-full",
            svgElement.amend(
              svg.width               := "100%",
              svg.height              := "100%",
              svg.preserveAspectRatio := "xMidYMid meet"
            )
          )
        )
      )

private def logThumbVisible(project: ProjectInfo): Unit =
  Telemetry.log("home.thumb.visible", "projectId" -> project.id.value, "name" -> project.name)

private def projectCard(graphviz: Graphviz, router: Router, info: Option[ProjectCardInfo], project: ProjectInfo) =
  val previewEnabled = Var(false)

  def enablePreview(): Unit =
    if !previewEnabled.now() then
      logThumbVisible(project)
      previewEnabled.set(true)

  div(
    cls := "project-card card",
    figure(
      // Preview SVG
      div(
        cls := "w-full h-48 overflow-hidden bg-base-200 flex items-center justify-center cursor-pointer",
        enableWhenFirstVisible(() => enablePreview()),
        child <-- previewEnabled.signal.flatMapSwitch:
          case false =>
            Signal.fromValue(
              div(
                cls := "w-full h-full p-1 flex items-center justify-center text-base-content/40 text-xs",
                "Loading preview…"
              )
            )
          case true =>
            projectThumbnail(graphviz, project)
        ,
        onClick.preventDefault --> router.navigateTo(Route.ProjectDetail(project.id.value))
      )
    ),
    div(
      cls := "card-body p-3 pt-2",

      // Header with title and delete button
      div(
        cls := "flex items-center justify-between",
        h2(
          cls := "card-title",
          a(
            href := s"#/${project.id.value}",
            cls  := "flex items-center gap-2 hover:text-primary transition-colors",
            info.map(_.displayName).getOrElse(project.name),
            onClick.preventDefault --> router.navigateTo(Route.ProjectDetail(project.id.value))
          ),
          info.map(i => formatBadge(i.format)),
          info.flatMap(kindBadge)
        ),
        // Neutral until you reach for it: a wall of cards should not read as a wall of red
        // buttons, but the hover has to say plainly that this one destroys something.
        IconButton("bi-trash", "Delete diagram", TooltipPos.left, cls := "danger")(
          if dom.window.confirm("Are you sure you want to delete this project?") then
            ProjectStorage.deleteProject(project.id)
        )
      ),

      // Last modified and created at dates
      div(
        cls := "text-sm text-base-content/70 flex flex-col gap-1",
        div(
          cls := "flex items-center gap-1",
          span().listIcon,
          s"Last modified: ${formatDate(project.lastModified)}"
        ),
        div(
          cls := "flex items-center gap-1",
          span().fileCodeIcon,
          s"Created: ${formatDate(project.createdAt)}"
        )
      )
    )
  )

/** The list-mode spelling of a project: one compact, fully clickable row —
  * a small (still lazy) thumbnail for recognition, then name, badge, dates as
  * scannable columns. Same information as the card minus the date icons; the
  * delete control keeps the card's exact behavior.
  */
private def projectRow(graphviz: Graphviz, router: Router, info: Option[ProjectCardInfo], project: ProjectInfo) =
  val previewEnabled = Var(false)

  def enablePreview(): Unit =
    if !previewEnabled.now() then
      logThumbVisible(project)
      previewEnabled.set(true)

  div(
    cls := "project-row",
    onClick --> router.navigateTo(Route.ProjectDetail(project.id.value)),
    div(
      cls := "row-thumb",
      enableWhenFirstVisible(() => enablePreview()),
      // Too small a box for a "Loading preview…" legend; the bg reads as a placeholder.
      child.maybe <-- previewEnabled.signal.flatMapSwitch:
        case false => Signal.fromValue(None)
        case true  => projectThumbnail(graphviz, project).map(Some(_))
    ),
    div(
      cls := "flex-1 min-w-0 flex items-center gap-2",
      a(
        href := s"#/${project.id.value}",
        cls  := "font-medium truncate hover:text-primary transition-colors",
        info.map(_.displayName).getOrElse(project.name),
        // The row already navigates; the anchor exists so middle-click and
        // "open in new tab" keep their usual meaning.
        onClick.preventDefault.stopPropagation --> router.navigateTo(Route.ProjectDetail(project.id.value))
      ),
      info.map(i => formatBadge(i.format)),
      info.flatMap(kindBadge)
    ),
    span(cls := "row-date hidden sm:block", s"Modified ${formatDate(project.lastModified)}"),
    span(cls := "row-date hidden md:block", s"Created ${formatDate(project.createdAt)}"),
    span(
      // The row navigates on click; without this fence, deleting would also open
      // the project (or, post-delete, a dead route).
      onClick.stopPropagation --> Observer.empty,
      IconButton("bi-trash", "Delete diagram", TooltipPos.left, cls := "danger")(
        if dom.window.confirm("Are you sure you want to delete this project?") then
          ProjectStorage.deleteProject(project.id)
      )
    )
  )

private def formatDate(timestamp: Long): String =
  val date = new js.Date(timestamp.toDouble)
  date.toLocaleDateString()
