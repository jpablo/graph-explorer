package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.ownership.ManualOwner
import munit.FunSuite
import org.jpablo.graphexplorer.library.LoggedChecks
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend
import org.jpablo.graphexplorer.viewer.domUtils.parseSVG
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.util.{Failure, Success, Try}

/** [[ThumbnailRenderer.hasDrawing]] tells a thumbnail with a drawing from a
  * thumbnail with no drawing.
  *
  * The thumbnail renderer does not put a render with no drawing in the disk
  * cache, and it ignores a stored SVG with no drawing. Thus the check must be
  * true for each correct render: Graphviz DOT output, and each Mermaid diagram
  * kind. This spec renders a sample of each kind (from the examples of the
  * gallery) and examines the SVG as the disk cache stores it.
  */
class ThumbnailDrawingSpec extends FunSuite with TestHelpers with LoggedChecks:

  def checkPrefix = "ThumbnailDrawingSpec"

  private val Svg = """xmlns="http://www.w3.org/2000/svg""""

  private def head(svg: dom.Element): String =
    svg.outerHTML.replaceAll("(?s)<style>.*?</style>", "<style>…</style>").take(400)

  /** The SVG as the disk cache gives it: serialized, then parsed again. */
  private def asStored(svg: dom.Element): dom.Element =
    parseSVG(svg.outerHTML).ref

  // --- SVGs with no drawing ---

  test("the SVG of a render whose id was on the page holds no drawing"):
    val svg = parseSVG(
      s"""<svg $Svg id="mermaid-render-2" width="100%" role="graphics-document document" aria-roledescription="flowchart-v2"><style>#mermaid-render-2{fill:#333;}</style><g/></svg>"""
    ).ref
    check(!ThumbnailRenderer.hasDrawing(svg), s"the check finds a drawing in: ${head(svg)}")

  test("an SVG with no elements holds no drawing"):
    val svg = parseSVG(s"""<svg $Svg/>""").ref
    check(!ThumbnailRenderer.hasDrawing(svg), s"the check finds a drawing in: ${head(svg)}")

  test("an SVG with only a style, a title, and empty groups holds no drawing"):
    val svg = parseSVG(
      s"""<svg $Svg viewBox="0 0 10 10"><style>rect { fill: red } text { fill: blue }</style><title>t</title><desc>d</desc><g><g class="nodes"/></g></svg>"""
    ).ref
    check(!ThumbnailRenderer.hasDrawing(svg), s"the check finds a drawing in: ${head(svg)}")

  // --- Each drawable element counts ---

  for element <- Seq(
      "<path d='M0 0L1 1'/>",
      "<rect width='1' height='1'/>",
      "<circle r='1'/>",
      "<ellipse rx='1' ry='1'/>",
      "<line x2='1' y2='1'/>",
      "<polyline points='0,0 1,1'/>",
      "<polygon points='0,0 1,1 1,0'/>",
      "<text>a</text>",
      "<image href='a.png' width='1' height='1'/>",
      "<use href='#a'/>",
      "<foreignObject width='1' height='1'><div xmlns='http://www.w3.org/1999/xhtml'>a</div></foreignObject>"
    )
  do
    test(s"an SVG with $element in a group holds a drawing"):
      val svg = parseSVG(s"""<svg $Svg><style>g{}</style><g><g>$element</g></g></svg>""").ref
      check(ThumbnailRenderer.hasDrawing(svg), s"the check finds no drawing in: ${head(svg)}")

  // --- Graphviz DOT renders ---

  private val DotSamples = Seq(
    "an empty graph"        -> "digraph G {\n}",
    "two nodes"             -> "digraph { a -> b }",
    "a cluster"             -> "digraph { subgraph cluster_x { label=X; a } a -> b }",
    "an HTML label"         -> "digraph { a [shape=plaintext label=<<b>bold</b> text>] }",
    "invisible nodes"       -> "digraph { node [style=invis]; edge [style=invis]; a -> b }",
    "a transparent graph"   -> "digraph { bgcolor=transparent; a -> b }",
    "the neato engine"      -> "graph { layout=neato; a -- b; b -- c }",
    "an undirected graph"   -> "graph { a -- b }"
  )

  for (name, source) <- DotSamples do
    test(s"the Graphviz render of $name holds a drawing"):
      withGraphviz: graphviz =>
        val svg = graphviz.textToSvgOnly(DotText(source)).get
        check(ThumbnailRenderer.hasDrawing(asStored(svg)), s"the check finds no drawing in: ${head(svg)}")

  // --- Mermaid renders, one for each diagram kind ---

  private val MermaidSamples = Seq(
    "flowchart" -> "flowchart TD\n  A-->B\n  A-->C",
    "empty flowchart" -> "%% Empty Mermaid graph\nflowchart TD",
    "flowchart with subgraphs and HTML labels" ->
      """---
        |title: Showcase
        |---
        |flowchart LR
        |classDef fixed fill:#bbf7d0,stroke:#15803d
        |subgraph Dialog [Edit Label Dialog]
        |  typed["You press Shift+Enter…<br/>…you get a line break"]:::fixed
        |end
        |subgraph Backend [Mermaid Backend]
        |  stored["Stored DOT-escaped"]
        |  subgraph Nested [Nested Subgraph]
        |    kept(("survives<br/>canvas edits")):::fixed
        |  end
        |end
        |out{{"Rendered with<br/>real line breaks"}}
        |typed -->|"escaped<br/>on save"| stored
        |stored -.->|serialize| out
        |stored ==> kept""".stripMargin,
    "sequence" ->
      """sequenceDiagram
        |  participant Browser
        |  participant API
        |  Browser->>API: GET /diagrams
        |  API-->>Browser: 200 OK (json)
        |  Note over Browser,API: rendered, not yet editable""".stripMargin,
    "class" ->
      """classDiagram
        |  class ViewerGraph {
        |    +nodes: VectorMap
        |    +subgraph()
        |  }
        |  class DiagramBackend {
        |    <<trait>>
        |    +render()
        |  }
        |  DiagramBackend <|-- MermaidBackend
        |  MermaidBackend --> ViewerGraph""".stripMargin,
    "state" ->
      """stateDiagram-v2
        |  [*] --> Idle
        |  Idle --> Selecting : click
        |  Selecting --> Idle : Escape
        |  Selecting --> [*] : close""".stripMargin,
    "er" ->
      """erDiagram
        |  PROJECT ||--o{ DIAGRAM : contains
        |  DIAGRAM ||--o{ NODE : has""".stripMargin,
    "gantt" ->
      """gantt
        |  title Release plan
        |  dateFormat YYYY-MM-DD
        |  section Backend
        |    Graph model      :done, a1, 2026-07-01, 10d
        |    DOT parity       :active, a2, after a1, 12d""".stripMargin,
    "pie" ->
      """pie showData
        |  title Lines of code by module
        |  "shared" : 42
        |  "viewer" : 35""".stripMargin,
    "mindmap" ->
      """mindmap
        |  root((Graph Explorer))
        |    Backends
        |      Graphviz
        |      Mermaid
        |    Canvas""".stripMargin,
    "timeline" ->
      """timeline
        |  title Project milestones
        |  2025-05 : First commit
        |  2026-07 : Mermaid parity push""".stripMargin,
    "gitGraph" ->
      """gitGraph
        |  commit id: "init"
        |  branch feature
        |  commit id: "work"
        |  checkout main
        |  merge feature""".stripMargin,
    "quadrant" ->
      """quadrantChart
        |  title Effort vs impact
        |  x-axis Low effort --> High effort
        |  y-axis Low impact --> High impact
        |  quadrant-1 Plan
        |  quadrant-2 Do first
        |  quadrant-3 Skip
        |  quadrant-4 Maybe
        |  Edge halos: [0.2, 0.8]""".stripMargin,
    "xychart" ->
      """xychart-beta
        |  title "Test suite growth"
        |  x-axis [Mar, Apr, May]
        |  y-axis "tests" 0 --> 300
        |  bar [80, 130, 180]
        |  line [80, 130, 180]""".stripMargin,
    "sankey" ->
      """sankey-beta
        |Source text,Parser,90
        |Parser,ViewerGraph,80
        |Parser,Errors,10""".stripMargin,
    "requirement" ->
      """requirementDiagram
        |  requirement selection_visibility {
        |    id: R1
        |    text: selected elements must stand out
        |    risk: low
        |    verifymethod: inspection
        |  }
        |  element focus_mode {
        |    type: feature
        |  }
        |  focus_mode - satisfies -> selection_visibility""".stripMargin,
    "C4" ->
      """C4Context
        |  title Graph Explorer context
        |  Person(user, "User", "Edits diagrams")
        |  System(gx, "Graph Explorer", "Browser app")
        |  Rel(user, gx, "Uses")""".stripMargin,
    "block" ->
      """block-beta
        |  columns 3
        |  Frontend blockArrowId<["API"]>(right) Backend
        |  space:3
        |  DB[("storage")]:3""".stripMargin,
    "kanban" ->
      """kanban
        |  Todo
        |    t1[Ports support]
        |  Done
        |    t4[Focus mode]""".stripMargin,
    "packet" ->
      """packet-beta
        |  0-15: "Source Port"
        |  16-31: "Destination Port"""".stripMargin,
    "radar" ->
      """radar-beta
        |  title Backend parity
        |  axis shapes, colors, labels
        |  curve dot{5, 5, 4}""".stripMargin,
    "architecture" ->
      """architecture-beta
        |  group app(cloud)[Browser]
        |  service ui(server)[Laminar UI] in app
        |  service model(database)[ViewerGraph] in app
        |  ui:R -- L:model""".stripMargin,
    "treemap" ->
      """treemap-beta
        |"Codebase"
        |  "shared"
        |    "backends": 40
        |  "viewer"
        |    "state": 20""".stripMargin,
    "journey" ->
      """journey
        |  title Fixing a rendering bug
        |  section Reproduce
        |    Open the diagram: 3: Dev
        |    See the glitch: 1: Dev""".stripMargin
  )

  for (kind, source) <- MermaidSamples do
    test(s"the Mermaid render of the $kind sample holds a drawing"):
      MermaidBackend().textToSvg(source).map: result =>
        val svg = result.svg.ref
        check(ThumbnailRenderer.hasDrawing(asStored(svg)), s"the check finds no drawing in: ${head(svg)}")

  // --- The card of a stored record that it cannot show ---

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

  /** Read the disk cache until the record of `source` satisfies `ok`. */
  private def storedRecord(format: DiagramFormat, source: String, what: String)(ok: String => Boolean): Future[String] =
    def loop(tries: Int): Future[String] =
      ThumbnailDiskCache.get(format, source).flatMap:
        case Some(svg) if ok(svg) => Future.successful(svg)
        case _ if tries > 0       => afterMicrotasks(()).flatMap(_ => loop(tries - 1))
        case other                => Future.fromTry(Try(check(false, s"$what: $other")).map(_ => fail(what)))
    loop(tries = 500)

  /** Put `record` in the disk cache for `source`, mount the thumbnail, and
    * give the SVG of the card and the record that the disk cache holds then. */
  private def cardFor(format: DiagramFormat, source: String, record: String): Future[(dom.svg.SVG, String)] =
    ThumbnailDiskCache.put(format, source, record)
    val owner = ManualOwner()
    storedRecord(format, source, "precondition: the disk cache does not hold the record")(_ == record)
      .flatMap: _ =>
        Graphviz.build().flatMap: graphviz =>
          val thumbnail = ThumbnailRenderer.processDotText(graphviz, DotText(source)).observe(using owner)
          eventually("the thumbnail left its skeleton"): () =>
            Some(thumbnail.now().ref).filterNot(_.classList.contains("skeleton"))
      .flatMap: svg =>
        storedRecord(format, source, "the disk cache still holds the record with no drawing")(_ != record)
          .map(svg -> _)
      .transform: result =>
        owner.killSubscriptions()
        result

  /** The card and the new record hold the drawing of the diagram. */
  private def assertDrawn(card: dom.svg.SVG, newRecord: String, nodes: Int): Unit =
    check(card.hasAttribute("viewBox"), s"the card shows an SVG with no viewBox: ${head(card)}")
    same(card.querySelectorAll("g.node").length, nodes, s"the card does not show the diagram: ${head(card)}")
    val stored = parseSVG(newRecord).ref
    check(ThumbnailRenderer.hasDrawing(stored), s"the new record holds no drawing: ${head(stored)}")
    same(stored.querySelectorAll("g.node").length, nodes, s"the new record does not hold the diagram: ${head(stored)}")

  test("a stored Mermaid record that is not SVG is drawn again and replaced"):
    val source = s"flowchart TD\n  A-->B\n  %% ${js.Date.now()}"
    cardFor(DiagramFormat.Mermaid, source, record = "<svg").map: (card, newRecord) =>
      assertDrawn(card, newRecord, nodes = 2)

  test("a stored DOT record with no drawing is drawn again and replaced"):
    val source = s"digraph { a -> b /* ${js.Date.now()} */ }"
    cardFor(DiagramFormat.DOT, source, record = s"""<svg $Svg width="8pt" height="8pt"><g/></svg>""").map:
      (card, newRecord) => assertDrawn(card, newRecord, nodes = 2)
