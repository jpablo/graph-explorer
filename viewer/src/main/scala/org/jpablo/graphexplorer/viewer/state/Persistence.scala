package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.state.Var
import org.jpablo.graphexplorer.projects.ProjectStorage
import org.jpablo.graphexplorer.viewer.models.ElementIds
import upickle.default.*

trait Persistence:
  this: ViewerState =>

  private val persistedState: Var[PersistedState] =
    ProjectStorage.loadProjectPersistedState(projectId)

  def restoreState() =
    val state0 = persistedState.now()
    // Restore ViewerState <~ PersistedStage (which comes from local storage)
    dom.console.debug("restoreState()")
    sourceText.set(state0.source)
    project.name.set(state0.projectName)
    project.hiddenElements.set(state0.hiddenNodes)
    rightPanelVisible.set(state0.rightPanelVisible)
    rightPanelTabIndex.set(state0.sideBarTabIndex)
    leftPanelVisible.set(state0.leftPanelVisible)
    // synchronize ViewerState ~> PersistedStage
    project.hiddenElements.signal
      .combineWith(
        project.name.signal,
        sourceText.signal,
        rightPanelVisible.signal,
        rightPanelTabIndex.signal,
        leftPanelVisible.signal
      )
      .map(PersistedState.apply)
      .foreach(persistedState.set)
  end restoreState


case class PersistedState(
  hiddenNodes:       HiddenElements = ElementIds(),
  projectName:       String = "",
  source:            String = "",
  rightPanelVisible: Boolean = true,
  sideBarTabIndex:   Int = 0,
  leftPanelVisible:  Boolean = true
) derives ReadWriter

object PersistedState:
  private val minimalGraphText = "digraph G {\n}"
  val empty =
    PersistedState(
      hiddenNodes       = ElementIds(),
      projectName       = "Untitled",
      source            = minimalGraphText,
      rightPanelVisible = true,
      sideBarTabIndex   = 0,
      leftPanelVisible  = true
    )
