package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.{ManualOwner, Owner}
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.utils.{TestHelpers, Waits}
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** An external label (`xlabel`) with HTML-like markup shows the markup as format, not as text.
  *
  * Found on 2026-11-28 on the desktop. In a diagram in the manual mode, the external label
  * `<abc<BR/><I>123</I>>` showed the text `abc<BR/><I>123</I>` on the canvas. The Edit External Label dialog
  * showed two lines, with `123` in italics.
  *
  * Graphviz draws an HTML-like label as one `text` element for each line. A label that is a plain string has one
  * `text` element with the markup in it.
  */
class XLabelMarkupSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "XLabelMarkupSpec"

  // Through the Scala.js test bridge and the sbt client, a munit failure arrives with no message. An AssertionError
  // keeps its message.
  override def check(condition: Boolean, clue: => String): Unit =
    if !condition then throw AssertionError(s"[$checkPrefix] $clue")

  override def munitFixtures = List(mockStorageFixture())

  private val dot =
    """digraph G {
      |  a [xlabel=<abc<BR/><I>123</I>>]
      |  a -> b
      |}""".stripMargin

  private def withView(name: String)(body: ViewerState => Future[Unit]): Future[Unit] =
    val owner = ManualOwner()
    TestHelpers.sharedGraphviz
      .flatMap: graphviz =>
        given Owner = owner
        val state   = ViewerState(ViewTarget.diagram(name), graphviz, initialSource = Some(dot))
        val drawing = state.svgWithPositions.observe
        Waits
          .settle(state.fullGraphNow().nodeIds.nonEmpty && drawing.now().nonEmpty, timeoutMs = 10000)
          .flatMap: _ =>
            check(drawing.now().nonEmpty, s"$name: precondition: the view did not draw the diagram")
            body(state)
      .transform: outcome =>
        owner.killSubscriptions()
        outcome

  /** The texts of the drawing of `state` now. */
  private def drawnTexts(state: ViewerState): Vector[String] =
    var shown = Vector.empty[String]
    state.svgWithPositions
      .foreach(_.foreach(d => shown = d.svg.ref.querySelectorAll("text").toSeq.map(_.textContent.trim).toVector))(using
        state.owner
      )
      .kill()
    shown

  /** The external label shows as two lines, `abc` and `123`, and no text has the markup. */
  private def showsFormat(what: String, shown: Vector[String]): Unit =
    check(
      !shown.exists(t => t.contains("<BR") || t.contains("<I>")),
      s"$what: the external label shows its markup as text: ${shown.mkString(" | ")}"
    )
    check(
      shown.contains("abc") && shown.contains("123"),
      s"$what: the external label does not show the two lines abc and 123: ${shown.mkString(" | ")}"
    )

  test("an automatic diagram shows the markup of an external label as format"):
    withView("xlabel-markup-auto"): state =>
      check(!state.layout.now().isManual, "precondition: the diagram is in the manual mode")
      showsFormat("automatic", drawnTexts(state))
      Future.unit

  test("a diagram in the manual mode shows the markup of an external label as format"):
    withView("xlabel-markup-manual"): state =>
      var drawn = Option.empty[dom.svg.SVG]
      state.svgWithPositions.foreach(d => drawn = d.map(_.svg.ref))(using state.owner)
      val before = drawn
      state.history.markGesture()
      state.freezeLayout()
      check(state.layout.now().isManual, "precondition: Freeze did not set the manual mode")
      Waits
        .settle(drawn.isDefined && !(drawn.get eq before.orNull) && state.drawsFreeLayoutNow, timeoutMs = 5000)
        .map: _ =>
          check(state.drawsFreeLayoutNow, "precondition: the view does not draw a free layout")
          showsFormat("manual", drawnTexts(state))
