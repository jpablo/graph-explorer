package org.jpablo.typeexplorer.viewer.components.state

import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import io.laminext.syntax.core.storedString
import org.jpablo.typeexplorer.viewer.graph.InheritanceGraph

/** In-memory App State
  */
class AppState(
    persistedAppState:         PersistentVar[PersistedAppState],
    projectId:                 ProjectId,
    fetchFullInheritanceGraph: List[Path] => Signal[InheritanceGraph]
)(using o: Owner):
  export activeProject.{
    basePaths,
    packagesOptions,
    diagramOptions,
    pages,
    update as updateActiveProject
  }

  val project: Signal[Project] =
    persistedAppState.signal.map(_.project)

  val activeProject: ActiveProject =
    val p = persistedAppState.zoom(_.project)((pas, p) => pas.copy(project = p))
    ActiveProject(PersistentVar(p))

  val fullGraph: Signal[InheritanceGraph] =
    activeProject.basePaths.flatMapSwitch(fetchFullInheritanceGraph)



  val appConfigDialogOpenV = Var(false)

object AppState:

  /** Load Projects from local storage and select the active project
    */
  def load(
      fetchFullInheritanceGraph: List[Path] => Signal[InheritanceGraph],
      projectId:                 ProjectId
  )(using Owner): AppState =
    AppState(
      persistentVar(
        storedString("persistedAppState", initial = "{}"),
        initial  = PersistedAppState(Project(ProjectId("???"))),
        fromJson = _ => Left("Ok"),
        toJson   = _ => "{}"
      ),
      projectId,
      fetchFullInheritanceGraph
    )

end AppState
