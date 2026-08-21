package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.Var
import com.softwaremill.quicklens.*
import org.jpablo.graphexplorer.viewer.models.GroupId

/** Convenience wrapper around a Var[Project]
  */
case class ProjectOps(project: Var[Project])(using Owner):

  val signal = project.signal

  val name: Var[String] =
    project.zoomLazy(_.name)((p, n) => p.copy(name = n)).distinct

  val hiddenElements: Var[HiddenElements] =
    project
      .zoomLazy(_.page.hiddenElements)((p, s) => p.modify(_.page.hiddenElements).setTo(s))
      .distinct

  val collapsedGroups: Var[Set[GroupId]] =
    project
      .zoomLazy(_.page.collapsedGroups)((p, s) => p.modify(_.page.collapsedGroups).setTo(s))
      .distinct

end ProjectOps
