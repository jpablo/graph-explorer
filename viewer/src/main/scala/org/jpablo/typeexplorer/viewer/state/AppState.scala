package org.jpablo.typeexplorer.viewer.state

import buildinfo.BuildInfo
import com.raquo.airstream.core.Signal
import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.viewer.graph.ViewerGraph

class AppState(
    projectV:      Var[Project],
    val fullGraph: Signal[ViewerGraph],
    version:       String = BuildInfo.version
)(using o: Owner):

  val project: ActiveProject =
    ActiveProject(projectV)

  val appConfigDialogOpenV = Var(false)
