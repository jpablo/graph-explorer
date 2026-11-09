package org.jpablo.graphexplorer.library

import com.raquo.laminar.api.L.*
import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.{DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.router.Router
import org.jpablo.graphexplorer.viewer.backends.DefaultDiagramLanguages
import org.jpablo.graphexplorer.viewer.desktop.DesktopDocumentRegistry
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js

/** The home page of the desktop, rendered: the cards and the rows of the
  * files, a missing file, the "Remove from library" control, and the notice of
  * a migration.
  *
  * The test renders the parts of the page, not the whole page: the whole page
  * has the About dialog, and the test build links a different `BuildInfo`
  * (the one of `shared`), which has no build time.
  */
class LibraryViewSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "LibraryViewSpec"

  override def munitFixtures = List(mockStorageFixture())

  override def afterEach(context: AfterEach): Unit =
    Library.restoreDefault()
    DesktopDocumentRegistry.reset()
    js.Dynamic.global.window.__TAURI__ = null

  private def after(ms: Int): Future[Unit] =
    val p = Promise[Unit]()
    js.timers.setTimeout(ms.toDouble)(p.success(()))
    p.future

  test("the home page of the desktop shows a missing file, Remove from library, and the notice"):
    val shell = FakeDesktopShell()
    shell.install()
    shell.files("/w/present.dot") = "digraph G {\n  label=\"Present diagram\"\n  a\n}"
    shell.putEntry(LibraryEntry("/w/present.dot", DiagramMetadata(), 2, 2))
    shell.putEntry(LibraryEntry("/w/gone.dot", DiagramMetadata(), 1, 1))
    val container = dom.document.createElement("div")
    dom.document.body.appendChild(container)
    DesktopLibrary
      .load()
      .flatMap: library =>
        Library.install(library)
        Library.showNotice(Vector("The migration made /docs/Graph Explorer/Notes.dot"))
        withGraphvizAsync: graphviz =>
          val router = Router()
          val languages = DefaultDiagramLanguages(graphviz)
          val summaries = library.indexNow().diagrams
          def info(d: DiagramSummary) = library.diagramCardInfo(d.id, languages)
          val root = render(
            container,
            div(
              libraryNotice,
              summaries.map(d => diagramCard(graphviz, router, info(d), d)),
              summaries.map(d => diagramRow(graphviz, router, info(d), d))
            )
          )
          after(400).map: _ =>
            val text    = container.textContent
            val removes = container.querySelectorAll("[aria-label='Remove from library']").length
            val deletes = container.querySelectorAll("[aria-label='Delete diagram']").length
            root.unmount()
            check(text.contains("Present diagram"), s"the card does not show the title in the source: $text")
            check(text.contains("gone"), s"the missing file is not in the list: $text")
            check(text.contains("missing"), s"the card does not say that the file is missing: $text")
            check(text.contains("Notes.dot"), s"the notice of the migration is not on the page: $text")
            same(removes, 4, "each card and each row has a Remove from library control")
            same(deletes, 0, "the desktop page says Delete")
      .transform: result =>
        container.parentNode.removeChild(container)
        shell.uninstall()
        result
