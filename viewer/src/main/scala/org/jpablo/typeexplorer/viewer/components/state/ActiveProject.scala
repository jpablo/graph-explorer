package org.jpablo.typeexplorer.viewer.components.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.Owner

/** Convenience wrapper around a Var[Project]
  */
case class ActiveProject(project: Var[Project])(using o: Owner):

  //  export project.{signal, update, updater}
  val signal = project.signal
  val update = project.update
  val updater = project.updater

  val basePaths: Signal[List[Path]] =
    project.signal.map(_.projectSettings.basePaths).distinct

  val name: Var[String] =
    project.zoom(_.name)((p, n) => p.copy(name = n))

  val packagesOptions: Signal[PackagesOptions] =
    project.signal.map(_.packagesOptions).distinct

  val projectSettings: Signal[ProjectSettings] =
    project.signal.map(_.projectSettings).distinct

  val diagramOptions: Signal[DiagramOptions] =
    project.signal.map(_.page.diagramOptions)

  def pageV: Var[Page] =
    project.zoom(_.page)((p, page) => p.copy(page = page))

  val pages: Signal[Page] =
    project.signal.map(_.page)

end ActiveProject
