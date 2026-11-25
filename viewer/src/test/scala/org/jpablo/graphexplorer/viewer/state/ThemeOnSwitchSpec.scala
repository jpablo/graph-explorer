package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.ManualOwner
import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.jpablo.graphexplorer.library.{Library, LoggedChecks}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** A switch of diagrams keeps the stored theme on the document.
  *
  * A click on a Library item changes the route. For the new route, the app
  * makes a new `ViewerState` inside the route signal (`Viewer.scala`,
  * `child <-- router.currentRoute.map`). Before, the new view set the default
  * theme (`drafting`, a light theme) on the document, and the stored theme
  * came after the route transaction. The new UI got its first style in the
  * default colors. The change to the stored theme then started the CSS color
  * transitions of the toolbar and the Library (150 ms to 200 ms), and the whole
  * UI faded from light to dark: the flicker of a switch.
  *
  * The test records each theme that a view writes to the document, from the
  * first view and from the view of a switch. It expects the stored theme only.
  */
class ThemeOnSwitchSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "ThemeOnSwitchSpec"

  override def munitFixtures = List(mockStorageFixture())

  /** A dark theme, so that it is not the default theme. */
  private val storedTheme = "workbench"

  private val dot     = "digraph G { a -> b; b -> c; }"
  private val mermaid = "sequenceDiagram\n  A->>B: hi\n  B-->>A: ok"

  test("a switch of diagrams writes only the stored theme to the document"):
    val startHref  = dom.window.location.href
    val startTheme = dom.document.documentElement.getAttribute("data-theme")
    Library.loadViewerSettings().update(_.copy(currentTheme = Some(storedTheme)))
    val written = mutable.ArrayBuffer.empty[(String, String)]
    val owners  = mutable.ArrayBuffer.empty[ManualOwner]
    withGraphvizAsync: graphviz =>
      // The route of the app: one diagram id, and a new view for each id.
      val route = Var("theme-switch-dot")
      def view(id: String): Div =
        val owner = ManualOwner()
        owners += owner
        val source = if id.endsWith("dot") then dot else mermaid
        ViewerState(
          ViewTarget.diagram(id),
          graphviz,
          _ => (),
          setTheme = theme =>
            written += id -> theme
            dom.document.documentElement.setAttribute("data-theme", theme),
          initialSource = Some(source)
        )(using owner)
        div(idAttr := s"view-$id")
      val box  = dom.document.createElement("div").asInstanceOf[dom.html.Element]
      dom.document.body.appendChild(box)
      val root = render(box, div(child <-- route.signal.map(view)))
      Future
        .successful(())
        .map: _ =>
          route.set("theme-switch-mermaid")
          route.set("theme-switch-dot")
          val wrong = written.filter(_._2 != storedTheme)
          check(written.nonEmpty, "precondition: no view wrote a theme")
          check(
            wrong.isEmpty,
            s"a view wrote a theme that is not the stored theme '$storedTheme': " +
              wrong.map((id, t) => s"$id -> $t").mkString(", ") +
              s" (all writes: ${written.map((id, t) => s"$id -> $t").mkString(", ")})"
          )
          check(
            dom.document.documentElement.getAttribute("data-theme") == storedTheme,
            s"after the switches, the document has the theme " +
              s"'${dom.document.documentElement.getAttribute("data-theme")}', not '$storedTheme'"
          )
        .transform: outcome =>
          root.unmount()
          box.parentNode.removeChild(box)
          owners.foreach(_.killSubscriptions())
          if startTheme == null then dom.document.documentElement.removeAttribute("data-theme")
          else dom.document.documentElement.setAttribute("data-theme", startTheme)
          dom.window.history.replaceState(null, "", startHref)
          outcome
