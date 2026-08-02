package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.Signal
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.models.{ElementIds, GroupId}
import org.scalajs.dom
import upickle.default.*

import scala.util.Try

trait Persistence:
  this: ViewerState =>

  protected val persistedDiagramState: Var[PersistedDiagramState] =
    ProjectStorage.createProjectPersistence(projectId, initialSource)

  private val viewerSettings: Var[ViewerSettings] =
    ProjectStorage.loadViewerSettings()

  /** Restores the persisted state values to the current ViewerState. */
  private def restorePersistedState(): Unit =
    val restoredDiagramState   = persistedDiagramState.now()
    val restoredViewerSettings = viewerSettings.now()

    project.hiddenElements.set(restoredDiagramState.hiddenElements)
    project.collapsedGroups.set(restoredDiagramState.collapsedGroups)
    restoredDiagramState.format
      .flatMap(format => Try(DiagramFormat.valueOf(format)).toOption)
      .foreach(formatSelection.set)

    Var.set(
      project.name -> restoredDiagramState.projectName,
      // app settings
      leftPanelVisible -> restoredViewerSettings.leftPanelVisible,
      rightPanelActiveSection -> Try(RightPanelSection.fromOrdinal(restoredViewerSettings.rightPanelTabIndex)).getOrElse(
        RightPanelSection.none
      ),
      currentTheme -> restoredViewerSettings.currentTheme,
      promptLabelBeforeNewNode -> restoredViewerSettings.promptLabelBeforeNewNode,
      // Re-clamp on restore: a width saved on a wide monitor would otherwise swallow the
      // canvas when the same library is opened in a narrow window.
      rightPanelWidth -> ViewerSettings.clampRightPanelWidth(
        restoredViewerSettings.rightPanelWidth.getOrElse(ViewerSettings.defaultRightPanelWidth),
        dom.window.innerWidth
      ),
      wrapSourceLines -> restoredViewerSettings.wrapSourceLines,
      elementsPinned -> restoredViewerSettings.elementsPinned
    )

  /** Sets up bidirectional synchronization between ViewerState and persisted storage. */
  private def setupStateSynchronization(): Unit =
    // synchronize ViewerState ~> PersistedState
    Signal
      .combine(
        project.hiddenElements.signal,
        project.collapsedGroups.signal,
        project.name.signal,
        sourceText.signal,
        formatSelection.signal
      )
      .changes
      .distinct
      .foreach: (hidden, collapsed, name, source, format) =>
        persistedDiagramState.set(
          PersistedDiagramState(
            hiddenElements = hidden,
            collapsedGroups = collapsed,
            projectName = name,
            source = source,
            format = Some(format.toString)
          )
        )

    // synchronize ViewerState ~> ViewerSettings
    Signal
      .combine(
        leftPanelVisible.signal,
        rightPanelActiveSection.signal,
        currentTheme.signal,
        promptLabelBeforeNewNode.signal,
        rightPanelWidth.signal,
        wrapSourceLines.signal,
        elementsPinned.signal
      )
      .changes
      .distinct
      .foreach((leftVisible, tabIndex, theme, promptBeforeNewNode, panelWidth, wrapLines, pinned) =>
        // copy, not a fresh ViewerSettings: fields this page does not own (the library's
        // view mode) must survive a detail-page sync instead of resetting to defaults.
        viewerSettings.update(
          _.copy(
            leftPanelVisible = leftVisible,
            rightPanelTabIndex = tabIndex.ordinal,
            currentTheme = theme,
            promptLabelBeforeNewNode = promptBeforeNewNode,
            rightPanelWidth = Some(panelWidth),
            wrapSourceLines = wrapLines,
            elementsPinned = pinned,
            schemaVersion = ViewerSettings.currentSchemaVersion
          )
        )
      )

  /** Initializes persistence by restoring state and setting up synchronization. */
  def initializePersistence(): Unit =
    restorePersistedState()
    setupStateSynchronization()

case class PersistedDiagramState(
    hiddenElements: HiddenElements = ElementIds(),
    // Folded groups, like hiddenElements: a view setting, saved with the page.
    collapsedGroups: Set[GroupId] = Set.empty,
    projectName:    String = "",
    source:         String = "",
    format:         Option[String] = None
) derives ReadWriter

object PersistedDiagramState:
  val minimalGraphText = "digraph G {\n}"

  /** The name given to never-renamed projects. Display sites substitute the diagram's
    * own declared title for it (ViewerState.displayTitle, ProjectStorage.projectCardInfo);
    * the stored name only changes when the user renames, so a rename always wins.
    */
  val defaultProjectName = "Untitled"

  val empty = minimal()

  def minimal(source: Option[String] = None) =
    PersistedDiagramState(
      hiddenElements = ElementIds(),
      collapsedGroups = Set.empty,
      projectName = defaultProjectName,
      source = source.getOrElse(minimalGraphText),
      format = None
    )

case class ViewerSettings(
    leftPanelVisible:   Boolean = true,
    rightPanelTabIndex: Int = 0,
    currentTheme:       Option[String] = None,
    promptLabelBeforeNewNode: Boolean = true,
    // None = never dragged, resolved to defaultRightPanelWidth on restore. Option rather than
    // a plain Int so "never chosen" stays distinguishable from a width that happens to equal
    // the default — the restore path treats the two differently.
    rightPanelWidth:    Option[Int] = None,
    wrapSourceLines:    Boolean = false,
    // Palette-first Elements list: false = floating card, true = docked in the panel.
    elementsPinned:     Boolean = false,
    // Library page: false = thumbnail cards, true = compact rows.
    libraryListMode:    Boolean = false,
    // Library page: the sort column, and the format the list is filtered to.
    // Stored as enum NAMES rather than the enums themselves, and tolerated
    // loosely on read: a ViewerSettings that fails to parse falls back to
    // `empty` and the sync then WRITES that back, so one stale name here would
    // cost the user every other setting. None = never chosen, matching
    // rightPanelWidth above (no filter / the default sort).
    librarySort:         Option[String] = None,
    libraryFormatFilter: Option[String] = None,
    schemaVersion:      Int = ViewerSettings.currentSchemaVersion // Add default for loading potentially older states
) derives ReadWriter

// Add a default empty state for ViewerSettings
object ViewerSettings:
  val currentSchemaVersion = 2 // Define the current version

  /** Right panel width in px. 320 = the 20rem this panel was fixed at before it could be dragged. */
  val defaultRightPanelWidth = 320

  /** Narrow enough to tuck the panel away, wide enough that the toolbar's controls still fit. */
  val minRightPanelWidth = 240

  /** A drag can only ever be as wide as this share of the window: the canvas is the point of
    * the app, so the panel must not be draggable over it. Recomputed per drag rather than
    * stored, since the window can be resized between sessions.
    */
  val maxRightPanelWidthFraction = 0.6

  /** Clamp a dragged width to something usable. Kept here, next to the bounds it enforces,
    * so the drag handler cannot drift from the persisted default.
    *
    * A window reporting no width at all — a backgrounded tab, a pane that has not painted yet —
    * caps nothing. Capping against 0 collapsed the panel to the minimum on restore, and the
    * settings sync then WROTE that back: one restore in a hidden window permanently shrank a
    * width the user had chosen.
    */
  def clampRightPanelWidth(px: Double, viewportWidth: Double): Int =
    val upper =
      if viewportWidth > 0 then math.max(minRightPanelWidth.toDouble, viewportWidth * maxRightPanelWidthFraction)
      else Double.MaxValue
    math.round(math.min(math.max(px, minRightPanelWidth.toDouble), upper)).toInt

  val empty = ViewerSettings()
