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
    val restoredState = persistedState.now()
    // Restore ViewerState <~ PersistedStage (which comes from local storage)
    project.hiddenElements.set(restoredState.hiddenElements)
    Var.set(
      project.name       -> restoredState.projectName,
      sourceText         -> restoredState.source,
      rightPanelVisible  -> restoredState.rightPanelVisible,
      rightPanelTabIndex -> restoredState.sideBarTabIndex,
      leftPanelVisible   -> restoredState.leftPanelVisible
    )
    // synchronize ViewerState ~> PersistedStage
    project.hiddenElements
      .signal
      .combineWithFn(project.name.signal, sourceText.signal, rightPanelVisible.signal, rightPanelTabIndex.signal, leftPanelVisible.signal)(
        (hidden, name, source, rightVisible, tabIndex, leftVisible) =>
          PersistedState(
            hiddenElements = hidden,
            projectName = name,
            source = source,
            rightPanelVisible = rightVisible,
            sideBarTabIndex = tabIndex,
            leftPanelVisible = leftVisible,
            schemaVersion = PersistedState.currentSchemaVersion // Always save with the current version
          )
      )
      .distinct
      .foreach(persistedState.set)
  end restoreState

case class PersistedState(
    hiddenElements:    HiddenElements = ElementIds(),
    projectName:       String = "",
    source:            String = "",
    rightPanelVisible: Boolean = true,
    sideBarTabIndex:   Int = 0,
    leftPanelVisible:  Boolean = true,
    schemaVersion:     Int = PersistedState.currentSchemaVersion // Add default for loading potentially older states
) derives ReadWriter

object PersistedState:
  val currentSchemaVersion = 1 // Define the current version
  val minimalGraphText = "digraph G {\n}"
  val empty =
    PersistedState(
      hiddenElements = ElementIds(),
      projectName = "Untitled",
      source = minimalGraphText,
      rightPanelVisible = true,
      sideBarTabIndex = 0,
      leftPanelVisible = true,
      schemaVersion = currentSchemaVersion // Use current version for new/empty state
    )
