package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.EventStream
import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.viewer.models.ElementIds
import upickle.default.*

import scala.util.Try

trait Persistence:
  this: ViewerState =>

  private val persistedDiagramState: Var[PersistedDiagramState] =
    ProjectStorage.loadProjectPersistedState(projectId, initialSource)

  private val viewerSettings: Var[ViewerSettings] =
    ProjectStorage.loadViewerSettings()

  def restoreState() =
    val restoredDiagramState   = persistedDiagramState.now()
    val restoredViewerSettings = viewerSettings.now()

    project.hiddenElements.set(restoredDiagramState.hiddenElements)

    Var.set(
      project.name -> restoredDiagramState.projectName,
      sourceText   -> restoredDiagramState.source,
      // app settings
      leftPanelVisible -> restoredViewerSettings.leftPanelVisible,
      rightPanelActiveSection -> Try(RightPanelSection.fromOrdinal(restoredViewerSettings.rightPanelTabIndex)).getOrElse(
        RightPanelSection.none
      ),
      currentTheme -> restoredViewerSettings.currentTheme
    )
    // synchronize ViewerState ~> PersistedState
    EventStream.combineWithFn(
      project.hiddenElements.signal.changes.distinct,
      project.name.signal.changes.distinct,
      sourceText.signal.changes.distinct
    )((hidden, name, source) =>
      PersistedDiagramState(
        hiddenElements = hidden,
        projectName = name,
        source = source
      )
    )
      .distinct
      .foreach(persistedDiagramState.set)

    // synchronize ViewerState ~> ViewerSettings
    EventStream.combineWithFn(
      leftPanelVisible.signal.changes.distinct,
      rightPanelActiveSection.signal.changes.distinct,
      currentTheme.signal.changes.distinct
    )((leftVisible, tabIndex, theme) =>
      ViewerSettings(
        leftPanelVisible = leftVisible,
        rightPanelTabIndex = tabIndex.ordinal,
        currentTheme = theme,
        schemaVersion = ViewerSettings.currentSchemaVersion // Always save with the current version
      )
    )
      .distinct
      .foreach(viewerSettings.set)
  end restoreState

case class PersistedDiagramState(
    hiddenElements: HiddenElements = ElementIds(),
    projectName:    String = "",
    source:         String = ""
) derives ReadWriter

object PersistedDiagramState:
  val minimalGraphText = "digraph G {\n}"

  val empty = minimal()

  def minimal(source: Option[String] = None) =
    PersistedDiagramState(
      hiddenElements = ElementIds(),
      projectName = "Untitled",
      source = source.getOrElse(minimalGraphText)
    )

case class ViewerSettings(
    leftPanelVisible:   Boolean = true,
    rightPanelTabIndex: Int = 0,
    currentTheme:       Option[String] = None,
    schemaVersion:      Int = ViewerSettings.currentSchemaVersion // Add default for loading potentially older states
) derives ReadWriter

// Add a default empty state for ViewerSettings
object ViewerSettings:
  val currentSchemaVersion = 1 // Define the current version
  val empty                = ViewerSettings()
