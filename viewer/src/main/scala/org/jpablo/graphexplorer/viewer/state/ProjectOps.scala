package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.softwaremill.quicklens.*

/** Convenience wrapper around a Var[Project]
  */
case class ProjectOps(project: Var[Project]):

  //  export project.{signal, update, updater}
  val signal = project.signal
  val update = project.update
  val updater = project.updater

  val name: Var[String] =
    project.zoomLazy(_.name)((p, n) => p.copy(name = n))

  val page: Var[Page] =
    project.zoomLazy(_.page)((p, page) => p.copy(page = page))

  val hiddenElements: Var[HiddenElements] =
    project.zoomLazy(_.page.hiddenElements)((p, s) => p.modify(_.page.hiddenElements).setTo(s))

  val basePaths: Signal[List[String]] =
    project.signal.map(_.projectSettings.basePaths).distinct

  val projectSettings: Signal[ProjectSettings] =
    project.signal.map(_.projectSettings).distinct

  val diagramOptions: Signal[DiagramOptions] =
    project.signal.map(_.page.diagramOptions)

end ProjectOps
