package org.jpablo.graphexplorer.router

import org.jpablo.graphexplorer.viewer.state.ProjectId
import org.scalajs.dom.window

def projectIdFromLocation(): Option[ProjectId] =
  window.location.pathname.split("/").lastOption.map(ProjectId.apply)

def navigateToProject(projectId: ProjectId): Unit =
  window.location.href = s"/${projectId.value}"

def navigateToHome(): Unit =
  window.location.href = "/"


