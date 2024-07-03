package org.jpablo.typeexplorer.viewer.state

import buildinfo.BuildInfo
import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph

class AppState(
    val fullGraph: Signal[ViewerGraph],
    version:       String = BuildInfo.version
)(using o: Owner):
  val appConfigDialogOpenV = Var(false)
  val project =
    ProjectOps(Var(Project(ProjectId("project-0"))))
