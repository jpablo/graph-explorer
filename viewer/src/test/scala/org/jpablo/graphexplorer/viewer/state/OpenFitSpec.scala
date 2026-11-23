package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.TestHelpers

import scala.concurrent.ExecutionContext.Implicits.global

/** A diagram opens with Auto on, and with the frame of the Fit button.
  *
  * Each open of a diagram (the library, a file, an example) makes a new
  * [[ViewerState]]. Before, that state started with Auto off and a zoom of 1.
  * Thus the diagram filled the canvas to its edges, and the zoom toolbar hid
  * the bottom of the diagram.
  */
class OpenFitSpec extends FunSuite with TestHelpers:

  import com.raquo.laminar.api.L.unsafeWindowOwner
  given com.raquo.airstream.ownership.Owner = unsafeWindowOwner

  override def munitFixtures = List(mockStorageFixture())

  /** The transform that the Fit button gives (`resetView`). */
  private def fitTransform(state: ViewerState): String =
    var shown = ""
    val sub   = state.transform.foreach(shown = _)
    state.resetView()
    sub.kill()
    shown

  private def transformNow(state: ViewerState): String =
    var shown = ""
    state.transform.foreach(shown = _).kill()
    shown

  test("a diagram opens with Auto on"):
    withGraphvizAsync { graphviz =>
      val state = ViewerState(ViewTarget.diagram("open-fit-auto"), graphviz, _ => ())
      afterMicrotasks {
        assert(state.autoFit.now(), "Auto is off when the diagram opens")
      }
    }

  test("a diagram opens with the frame of the Fit button"):
    withGraphvizAsync { graphviz =>
      val opened   = ViewerState(ViewTarget.diagram("open-fit-frame"), graphviz, _ => ())
      val fitted   = ViewerState(ViewTarget.diagram("open-fit-frame-ref"), graphviz, _ => ())
      afterMicrotasks {
        assertEquals(transformNow(opened), fitTransform(fitted))
      }
    }
