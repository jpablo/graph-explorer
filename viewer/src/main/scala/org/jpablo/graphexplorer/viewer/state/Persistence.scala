package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.Signal
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.viewer.models.ElementIds
import upickle.default.*

import scala.util.Try

trait Persistence:
  this: ViewerState =>

  protected val persistedDiagramState: Var[PersistedDiagramState] =
    ProjectStorage.loadProjectPersistedState(projectId, initialSource)

  private val viewerSettings: Var[ViewerSettings] =
    ProjectStorage.loadViewerSettings()

  def restoreState() =
    val restoredDiagramState   = persistedDiagramState.now()
    val restoredViewerSettings = viewerSettings.now()

    project.hiddenElements.set(restoredDiagramState.hiddenElements)

    // Note: the stored source text is used already in InternalPhases
    Var.set(
      project.name -> restoredDiagramState.projectName,
      // app settings
      leftPanelVisible -> restoredViewerSettings.leftPanelVisible,
      rightPanelActiveSection -> Try(RightPanelSection.fromOrdinal(restoredViewerSettings.rightPanelTabIndex)).getOrElse(
        RightPanelSection.none
      ),
      currentTheme -> restoredViewerSettings.currentTheme
    )
    // synchronize ViewerState ~> PersistedState
    Signal
      .combine(project.hiddenElements.signal, project.name.signal, sourceText.signal)
      .changes
      .distinct
      .foreach: (hidden, name, source) =>
        persistedDiagramState.set(
          PersistedDiagramState(hidden, name, source)
        )

    // synchronize ViewerState ~> ViewerSettings
    Signal.combine(leftPanelVisible.signal, rightPanelActiveSection.signal, currentTheme.signal)
      .changes
      .distinct
      .foreach((leftVisible, tabIndex, theme) =>
        viewerSettings.set(
          ViewerSettings(
            leftPanelVisible = leftVisible,
            rightPanelTabIndex = tabIndex.ordinal,
            currentTheme = theme,
            schemaVersion = ViewerSettings.currentSchemaVersion // Always save with the current version
          )
        )
      )

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
