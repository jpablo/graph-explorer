package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

/** A thumbnail with no drawing in the disk cache must not blank its card.
  *
  * A Mermaid render whose id was also on the page gave an SVG with only a
  * style and an empty group (see RenderIdClashSpec). The disk cache stored it
  * under the source of the diagram. The key does not change until the source
  * changes, so the card showed the empty SVG in each later session. The
  * libraries of real users hold such records now.
  */
class StoredEmptyThumbnailSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "StoredEmptyThumbnailSpec"

  /** A record of that kind, as the cache of a real user holds it (the style is shorter). */
  private val EmptyRender =
    """<svg xmlns="http://www.w3.org/2000/svg" id="mermaid-render-2" width="100%" role="graphics-document document" aria-roledescription="flowchart-v2"><style>#mermaid-render-2{fill:#333;}</style><g/></svg>"""

  /** Probe every 20ms until `probe` gives a value. */
  private def eventually[A](what: String, timeoutMs: Double = 15000)(probe: () => Option[A]): Future[A] =
    val p       = Promise[A]()
    val startAt = js.Date.now()
    def loop(): Unit =
      Try(probe()) match
        case Success(Some(a)) => p.success(a)
        case Failure(e)       => p.failure(e)
        case Success(None) =>
          if js.Date.now() - startAt > timeoutMs then p.complete(Try(check(false, s"timed out: $what")).map(_ => fail(what)))
          else js.timers.setTimeout(20)(loop())
    loop()
    p.future

  private def head(svg: dom.svg.SVG): String =
    svg.outerHTML.replaceAll("(?s)<style>.*?</style>", "<style>…</style>").take(400)

  test("a stored Mermaid thumbnail with no drawing is drawn again"):
    // A source that no earlier run stored.
    val source = s"flowchart TD\n  A-->B\n  %% ${js.Date.now()}"
    ThumbnailDiskCache.put(DiagramFormat.Mermaid, source, EmptyRender)
    val owner = ManualOwner()

    // The write is fire-and-forget, so read until the record is there.
    def stored(tries: Int): Future[Unit] =
      ThumbnailDiskCache.get(DiagramFormat.Mermaid, source).flatMap:
        case Some(svg) if svg == EmptyRender => Future.unit
        case _ if tries > 0                  => afterMicrotasks(()).flatMap(_ => stored(tries - 1))
        case other => Future.fromTry(Try(check(false, s"precondition: the disk cache does not hold the empty record: $other")))

    stored(tries = 500)
      .flatMap: _ =>
        withGraphvizAsync: graphviz =>
          val thumbnail = ThumbnailRenderer.processDotText(graphviz, DotText(source)).observe(using owner)
          eventually("the thumbnail left its skeleton"): () =>
            Some(thumbnail.now().ref).filterNot(_.classList.contains("skeleton"))
          .map: svg =>
            check(svg.hasAttribute("viewBox"), s"the card shows an SVG with no viewBox: ${head(svg)}")
            same(svg.querySelectorAll("g.node").length, 2, s"the card does not show the diagram: ${head(svg)}")
      .transform: result =>
        owner.killSubscriptions()
        result
