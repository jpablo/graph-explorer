package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import upickle.default.{read, write}

import scala.concurrent.ExecutionContext.Implicits.global

class PersistenceSpec extends FunSuite with TestHelpers:

  override def munitFixtures = List(mockStorageFixture())

  def storedProjectKey(projectId: ProjectId): String =
    s"[StoredString]graph-explorer.project.${projectId.value}"

  test("Adding a node with smart connection should store the project in localStorage") {
    withGraphvizAsync { graphviz =>

      val projectName = "my project"
      val projectId   = ProjectId("test")
      val viewerState = ViewerState(projectId, graphviz)

      afterMicrotasks {
        // sanity check
        assertEquals(viewerState.fullGraphNow(), ViewerGraph.minimal)

        assertEquals(dom.window.localStorage.length, 0)

        viewerState.project.name.set(projectName)
        viewerState.addNodeWithSmartConnection()

        assertEquals(
          obtained = dom.window.localStorage.length,
          expected = 2,
          "Should have two items in localStorage: one for the project and one for the graph explorer version"
        )

        val storedProjectStr = dom.window.localStorage.getItem(storedProjectKey(projectId))

        assertEquals(
          storedProjectStr,
          s"""{"projectName":"$projectName","source":"digraph \\"G\\" {\\n  \\"a\\" [label=\\"\\"];\\n}","format":"DOT"}"""
        )

        // ---- verify ---
        assertEquals(viewerState.allNodeIds().size, 1)
        assertEquals(viewerState.allArrowIds().size, 0)
      }
    }
  }

  /** Settings written before a field existed must still load as that field's default. */
  test("settings JSON missing the newer keys loads their defaults, not zeros"):
    val settings = read[ViewerSettings]("""{"rightPanelTabIndex":3}""")
    assertEquals(settings.rightPanelWidth, None)
    assertEquals(settings.rightPanelWidth.getOrElse(ViewerSettings.defaultRightPanelWidth), 320)
    assertEquals(settings.wrapSourceLines, false)
    assertEquals(settings.promptLabelBeforeNewNode, true)

  test("a stored width round-trips"):
    val settings = read[ViewerSettings](write(ViewerSettings(rightPanelWidth = Some(512))))
    assertEquals(settings.rightPanelWidth, Some(512))

  test("panel width clamps to the minimum and to a share of the viewport"):
    import ViewerSettings.{clampRightPanelWidth, defaultRightPanelWidth, minRightPanelWidth}
    // dragged past the left edge of the world
    assertEquals(clampRightPanelWidth(-500, 1280), minRightPanelWidth)
    // dragged over the canvas: capped at 60% of the window
    assertEquals(clampRightPanelWidth(2000, 1280), 768)
    // an ordinary width is untouched
    assertEquals(clampRightPanelWidth(defaultRightPanelWidth, 1280), defaultRightPanelWidth)
    // a window narrower than the minimum still yields a usable panel
    assertEquals(clampRightPanelWidth(300, 200), minRightPanelWidth)

  /** A window that reports no width must not be treated as a window 0px wide.
    *
    * This is the bug this pair of tests exists for: restoring while the tab was backgrounded
    * (innerWidth == 0) capped the width at max(min, 0) — every panel snapped to the minimum,
    * and because the settings sync writes whatever the state holds, the shrunken value was
    * persisted over the width the user had dragged.
    */
  test("a viewport reporting zero width caps nothing"):
    import ViewerSettings.clampRightPanelWidth
    assertEquals(clampRightPanelWidth(480, 0), 480)
    assertEquals(clampRightPanelWidth(480, -1), 480)
    // the minimum still holds, since it does not depend on the viewport
    assertEquals(clampRightPanelWidth(10, 0), ViewerSettings.minRightPanelWidth)
