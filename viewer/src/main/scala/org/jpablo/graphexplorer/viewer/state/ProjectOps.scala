package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.Var

/** Convenience wrapper around a Var[Project]
  */
case class ProjectOps(project: Var[Project])(using Owner):

  //  export project.{signal, update, updater}
  val signal  = project.signal
  val update  = project.update
  val updater = project.updater

  val name: Var[String] =
    project.zoomLazy(_.name)((p, n) => p.copy(name = n)).distinct

  val page: Var[Page] =
    project.zoomLazy(_.page)((p, page) => p.copy(page = page)).distinct

  val hiddenElements: Var[HiddenElements] =
    project
      .zoomLazy(_.page.hiddenElements)((p, s) => p.modify(_.page.hiddenElements).setTo(s))
      .distinct

//  hiddenElements.signal.foreach: hidden =>
//    dom.console.debug(s"hidden elements changed: $hidden")

  val basePaths: Signal[List[String]] =
    project.signal.map(_.projectSettings.basePaths).distinct

  val projectSettings: Signal[ProjectSettings] =
    project.signal.map(_.projectSettings).distinct

  val diagramOptions: Signal[DiagramOptions] =
    project.signal.map(_.page.diagramOptions)

end ProjectOps
