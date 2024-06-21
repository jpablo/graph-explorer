package org.jpablo.typeexplorer.viewer.components.state

//import org.jpablo.typeexplorer.BuildInfo
import buildinfo.BuildInfo

/** Structure of the persisted state (in local storage)
  */
case class PersistedAppState(
    projects:            Map[ProjectId, Project] = Map.empty,
    lastActiveProjectId: Option[ProjectId] = None,
    version:             String = BuildInfo.version
):

  def selectOrCreateProject(projectId: ProjectId): Project =
    projects.getOrElse(projectId, Project(projectId))

  def deleteProject(projectId: ProjectId): PersistedAppState =
    copy(
      projects = projects - projectId,
      lastActiveProjectId =
        if lastActiveProjectId.contains(projectId) then None
        else lastActiveProjectId
    )
