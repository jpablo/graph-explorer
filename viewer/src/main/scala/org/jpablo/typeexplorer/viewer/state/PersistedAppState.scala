package org.jpablo.typeexplorer.viewer.state

import buildinfo.BuildInfo

/** Structure of the persisted state (in local storage)
  */
case class PersistedAppState(
    project: Project,
    version: String = BuildInfo.version
)
