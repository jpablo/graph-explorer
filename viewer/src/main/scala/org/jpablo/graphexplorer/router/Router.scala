package org.jpablo.graphexplorer.router

import org.jpablo.graphexplorer.viewer.state.ProjectId
import org.scalajs.dom.window

def projectIdFromLocation(): Option[ProjectId] =
  window.location.hash match
    case hash if hash.startsWith("#/") =>
      // Extract UUID after #/
      val uuid = hash.substring(2).trim
      if uuid.nonEmpty then
        Some(ProjectId(uuid))
      else
        None
    case _ => None

def navigateToProject(projectId: ProjectId): Unit =
  window.location.hash = s"#/${projectId.value}"
  window.location.reload()

def navigateToHome(): Unit =
  window.location.href = "/"
