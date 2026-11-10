package org.jpablo.graphexplorer.viewer.backends.mermaid

import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

/** A Mermaid render must not use the SVGs of other diagrams on the page.
  *
  * Mermaid finds the element that it draws into by its id, and it looks in the
  * whole document. Before a render, it also removes the first element that has
  * that id. The library page shows thumbnails from the disk cache, and those
  * SVGs come from earlier sessions. Each session numbers its renders from 1.
  * Thus a thumbnail from an earlier session can have the id of a new render.
  *
  * With one such thumbnail on the page, Mermaid removes it, and its card goes
  * blank. With two, Mermaid removes the first and draws into the second. The
  * new SVG then holds only a style and an empty group. The disk cache stores
  * that SVG, and the card of the new diagram is blank in each later session.
  */
class RenderIdClashSpec extends FunSuite with LoggedChecks:

  def checkPrefix = "RenderIdClashSpec"

  /** The number of render ids of an earlier session that the page shows. It
    * must be larger than the number of Mermaid renders in this test run. The
    * precondition in [[render]] tells you when to make it larger.
    */
  private val IdsOfEarlierSession = 256

  private val Flowchart = "flowchart TD\n  A-->B\n  A-->C"
  private val Sequence  = "sequenceDiagram\n  participant A\n  participant B\n  A->>B: hello\n  B-->>A: done"

  /** A thumbnail SVG as an earlier session stored it. */
  private def earlierThumbnail(id: String, copy: Int): String =
    s"""<svg xmlns="http://www.w3.org/2000/svg" id="$id" data-copy="$copy" viewBox="0 0 10 10"><g><rect width="10" height="10"/></g></svg>"""

  /** Put `copies` thumbnails of an earlier session on the page for each render
    * id of that session, run `body`, and then remove them. Mermaid appends its
    * scratch element at the end of `<body>`. Thus the thumbnails come first in
    * the document, as the cards of the library page do.
    */
  private def withEarlierThumbnails[A](copies: Int)(body: dom.Element => Future[A]): Future[A] =
    val page = dom.document.createElement("div")
    page.innerHTML =
      (for n <- 1 to IdsOfEarlierSession; c <- 1 to copies yield earlierThumbnail(s"mermaid-render-$n", c)).mkString
    dom.document.body.appendChild(page)
    Future.unit.flatMap(_ => body(page)).transform: result =>
      page.remove()
      result

  private def thumbnailsOf(page: dom.Element): Vector[String] =
    (0 until page.children.length).map(i => page.children(i).outerHTML).toVector

  private def render(source: String): Future[dom.svg.SVG] =
    MermaidBackend().textToSvg(source).map: result =>
      val svg = result.svg.ref
      // Precondition, not the bug: this test can only show a clash for the ids that it put on the page.
      Option(svg.getAttribute("id")).collect { case s"mermaid-render-$n" if n.toIntOption.exists(_ > IdsOfEarlierSession) => n }
        .foreach(n => check(false, s"precondition: render id $n is above $IdsOfEarlierSession; make IdsOfEarlierSession larger"))
      svg

  private def head(svg: dom.svg.SVG): String =
    svg.outerHTML.replaceAll("(?s)<style>.*?</style>", "<style>…</style>").take(400)

  /** The render has a viewport and holds the drawing of the diagram. */
  private def assertComplete(svg: dom.svg.SVG, drawn: String, expected: Int): Unit =
    check(svg.hasAttribute("viewBox"), s"the render has no viewBox: ${head(svg)}")
    same(svg.querySelectorAll(drawn).length, expected, s"the render does not hold the drawing: ${head(svg)}")

  /** The thumbnails of the earlier session are all on the page, and the render did not change them. */
  private def assertUnchanged(page: dom.Element, before: Vector[String]): Unit =
    val after = thumbnailsOf(page)
    same(after.size, before.size, "the render removed a thumbnail of an earlier session from the page")
    val changed = before.indices.filter(i => before(i) != after(i))
    check(changed.isEmpty, s"the render changed ${changed.size} thumbnails of an earlier session, the first: ${after(changed.head).take(300)}")

  test("a render leaves one earlier thumbnail with the same id on the page"):
    withEarlierThumbnails(copies = 1): page =>
      val before = thumbnailsOf(page)
      render(Flowchart).map: svg =>
        assertComplete(svg, "g.node", 3)
        assertUnchanged(page, before)

  test("a flowchart render is complete when two earlier thumbnails have its id"):
    withEarlierThumbnails(copies = 2): page =>
      val before = thumbnailsOf(page)
      render(Flowchart).map: svg =>
        assertComplete(svg, "g.node", 3)
        assertUnchanged(page, before)

  test("a sequence render is complete when two earlier thumbnails have its id"):
    withEarlierThumbnails(copies = 2): page =>
      val before = thumbnailsOf(page)
      render(Sequence).map: svg =>
        assertComplete(svg, "text.messageText", 2)
        assertUnchanged(page, before)
