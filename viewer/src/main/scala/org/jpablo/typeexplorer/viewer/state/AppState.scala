package org.jpablo.typeexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph

/** In-memory App State
  */
class AppState(
    persistedAppState: Var[PersistedAppState],
    val fullGraph:     Signal[ViewerGraph]
)(using o: Owner):
  export activeProject.{basePaths, packagesOptions, diagramOptions, pages, update as updateActiveProject}

  val project: Signal[Project] =
    persistedAppState.signal.map(_.project)

  val activeProject: ActiveProject =
    ActiveProject(persistedAppState.zoom(_.project)((pas, p) => pas.copy(project = p)))

  val appConfigDialogOpenV = Var(false)
