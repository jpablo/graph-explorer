package org.jpablo.typeexplorer.ui.app.components.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.OneTimeOwner
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import com.softwaremill.quicklens.*
import io.laminext.syntax.core.storedString
import org.jpablo.typeexplorer.shared.inheritance.{
  DiagramOptions,
  InheritanceDiagram
}
import org.jpablo.typeexplorer.ui.app.Path
import org.jpablo.typeexplorer.ui.app.components.state.InheritanceTabState.ActiveSymbols

/** In-memory App State
  */
case class AppState(
    persistedAppState: Var[PersistedAppState],
    activeProject: ProjectVar,
    inheritanceTabState: InheritanceTabState = InheritanceTabState()
):
  export activeProject.{
    basePaths,
    activeSymbols,
    packagesOptions,
    diagramOptions,
    update as updateActiveProject
  }

/** Convenience wrapper around a Var[Project]
  */
case class ProjectVar(project: Var[Project])(using Owner):

  export project.{signal, update, updater}

  val basePaths: Signal[List[Path]] =
    project.signal.map(_.basePaths)

  val packagesOptions: Signal[PackagesOptions] =
    project.signal.map(_.packagesOptions)

  val diagramOptions: Signal[DiagramOptions] =
    project.signal.map(_.diagramOptions)

  val activeSymbols: Var[ActiveSymbols] =
    project
      .zoom(_.activeSymbols.toMap) { (activeProject, activeSymbols) =>
        activeProject
          .modify(_.activeSymbols)
          .setTo(activeSymbols.toList)
      }
end ProjectVar

object AppState:

  /** Load Projects from local storage and select the active project
    */
  def load(
      fetchDiagram: List[Path] => Signal[InheritanceDiagram],
      projectId: Option[ProjectId] = None
  ): AppState =
    given owner: Owner = OneTimeOwner(() => ())

    val persistedAppState =
      persistent(
        storedString("persistedAppState", initial = "{}"),
        initial = PersistedAppState()
      )
    val activeProject =
      ProjectVar(selectProject(persistedAppState, projectId))

    AppState(
      persistedAppState,
      activeProject,
      InheritanceTabState(
        activeSymbolsR = activeProject.activeSymbols,
        fullInheritanceDiagram = activeProject.basePaths.flatMap(fetchDiagram)
      )
    )

  def selectProject(
      persistedAppState: Var[PersistedAppState],
      projectId: Option[ProjectId]
  )(using Owner): Var[Project] =
    persistedAppState
      .zoom(_.selectProject(projectId)) {
        (persistedAppState, selectedProject) =>
          persistedAppState
            .modify(_.projects)
            .using(_ + (selectedProject.id -> selectedProject))
      }

end AppState
