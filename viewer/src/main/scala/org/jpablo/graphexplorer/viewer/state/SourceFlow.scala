package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.DotText
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.ast.viewerGraph.graphToDotAST
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models
import org.jpablo.graphexplorer.viewer.models.NodeId
import org.jpablo.graphexplorer.viewer.utils.ChangeOrigin.CodeMirror
import org.jpablo.graphexplorer.viewer.utils.{ChangeOrigin, Version}

import scala.scalajs.js.Date

var lastDate: Date = null
var step = 0

enum Level:
  case Debug, Info, Warn, Error, None

  def toConsole = this match
    case Debug => dom.console.debug(_)
    case Info  => dom.console.info(_)
    case Warn  => dom.console.warn(_)
    case Error => dom.console.error(_)
    case None  => (_: Any) => ()

import org.jpablo.graphexplorer.viewer.state.Level.*

def timeDelta() =
  if lastDate == null then
    lastDate = new Date()
  val currentDate = new Date()
  val delta = currentDate.getTime() - lastDate.getTime()
  lastDate = currentDate
  s"${delta / 1000.0} s,  at: ${currentDate.toISOString().split('T')(1)}"

inline def withLog[A](
    label:     String,
    resetStep: Boolean = false,
    level:     Level = None
)(body: => A): A =
  step = if resetStep then 1 else step + 1
  val numberedLabel = s"($step) $label"
  val fn = level.toConsole
  fn(s"$numberedLabel [-->]: ${timeDelta()}")
  timeDelta()
  val a = body
//  fn(s"$numberedLabel [<--]: ${timeDelta()}")
  a

def simpleLog(label: String, level: Level = None): Unit =
  level.toConsole(label)

case class Versioned[A](value: A, version: Version, origin: ChangeOrigin)

class SourceFlow(
    initialSource: String,
    hiddenNodes:   Signal[Set[NodeId]],
    resetView:     () => Unit
)(using Owner):

  private val versionedText = Var(Versioned("", 0, ChangeOrigin.CodeMirror))

  // updated by CodeMirror
  val sourceText: Var[String] = Var("")

  private val versionedFullGraphV = Var(Versioned(ViewerGraph.empty, 0, ChangeOrigin.CodeMirror))

  // updated by Graph
  val fullGraphV: Var[ViewerGraph] = Var(ViewerGraph.empty)

  val fullGraph = fullGraphV.signal

  // sourceAST depends on versionedText (CodeMirror) and fullGraphV (Graph)
  private val sourceAST: Var[Versioned[DotAST]] = Var(Versioned(DotAST.empty, 0, ChangeOrigin.CodeMirror))

  // -------------------------------
  // sourceText <-> versionedText
  // -------------------------------

  // origin: CodeMirror
  for (newSource, Versioned(source, version, origin)) <- sourceText.signal.withCurrentValueOf(versionedText.signal)
  do
    val sourceChange = newSource != source
    withLog(
      s"[sourceText -> versionedText] (change: $sourceChange, v: $version, o: $origin)",
      resetStep = true
    ) {
      if sourceChange then
        versionedText.set(Versioned(newSource, version + 1, ChangeOrigin.CodeMirror))
      else
        simpleLog(s"[sourceText -> versionedText] skip")
    }

  // origin: Both
  for Versioned(newSource, v, o) <- versionedText.signal do
    withLog(s"[versionedText -> sourceText] (v: $v, o: $o)"):
      if sourceText.now() != newSource then
        sourceText.set(newSource)
      else
        simpleLog(s"[versionedText -> sourceText] skip")

  // -------------------------------
  // versionedText <-> sourceAST
  // -------------------------------

  // origin: CodeMirror
  for Versioned(newSource, newVersion, newOrigin) <- versionedText.signal do
    withLog(s"[versionedText -> sourceAST] (v: $newVersion, o: $newOrigin)"):
      val newAST = DotText(newSource)
        .parseAST
        .headOption
        .getOrElse(DotAST.empty)
        .attachInternalAttributes
      val Versioned(ast, astVersion, astOrigin) = sourceAST.now()
      if ast == newAST || newVersion <= astVersion then
        simpleLog(s"[sourceText -> sourceAST] skip")
      else
        sourceAST.set(Versioned(newAST, newVersion, newOrigin))

  // origin: Both
  for (Versioned(newAST, newVersion, newOrigin), Versioned(source, version, origin)) <-
      sourceAST.signal.withCurrentValueOf(versionedText.signal)
  do
    withLog(s"[sourceAST -> versionedText] (v: $newVersion, o: $newOrigin)"):
      // this will remove extra spaces and newlines
      val newSource = newAST.optimize.render(keepInternal = false)
      if newSource != source && newOrigin != ChangeOrigin.CodeMirror then
        versionedText.set(Versioned(newSource, newVersion, newOrigin))
      else
        simpleLog(s"[sourceAST -> versionedText] skip")

  // -------------------------------
  // sourceAST <-> versionedFullGraphV
  // -------------------------------
  sourceAST.signal.foreach { case Versioned(ast: DotAST, astVersion, astOrigin) =>
    withLog(s"[sourceAST -> versionedFullGraphV] (v: $astVersion, o: $astOrigin)"):
      val newGraph = ast.toViewerGraph
      val versionedGraph = versionedFullGraphV.now()
      if versionedGraph.value == newGraph || astVersion <= versionedGraph.version then
        simpleLog(s"[sourceAST -> versionedFullGraphV] skip")
      else
        versionedFullGraphV.set(Versioned(newGraph, astVersion, astOrigin))
  }

  // origin: Both
  versionedFullGraphV.signal.foreach { case Versioned(newGraph, newVersion, newOrigin) =>
    withLog(s"[versionedFullGraphV -> sourceAST] (v: $newVersion, o: $newOrigin)"):
      // whoever modified the graph should increment the version, so we don't need to do it here
      val newAST = graphToDotAST(newGraph)
      val Versioned(ast, astVersion, origin) = sourceAST.now()

      if ast != newAST && newOrigin != CodeMirror then
        sourceAST.set(Versioned(newAST, newVersion, newOrigin))
      else
        simpleLog(s"[versionedFullGraphV -> sourceAST] skip")
  }

  // -------------------------------
  // versionedFullGraphV <-> fullGraphV
  // -------------------------------

  // origin: Both
  versionedFullGraphV.signal.foreach { case Versioned(newGraph, newVersion, newOrigin) =>
    withLog(s"[versionedFullGraphV -> fullGraphV] (v: $newVersion, o: $newOrigin)"):
      val graph = fullGraphV.now()
      if graph == newGraph then
        simpleLog(s"[versionedFullGraphV -> fullGraphV] skip")
      else
        fullGraphV.set(newGraph)
  }

  // origin: Graph
  fullGraphV.signal.foreach { newGraph =>
    withLog(s"[fullGraphV -> versionedFullGraphV]", resetStep = true):
      val versionedGraph = versionedFullGraphV.now()
      if versionedGraph.value != newGraph then
        versionedFullGraphV.set(Versioned(newGraph, versionedGraph.version + 1, ChangeOrigin.Graph))
      else
        simpleLog(s"[fullGraphV -> versionedFullGraphV] skip")
  }

  dom.console.debug(s"setting initialSource: $initialSource")
  sourceText.set(initialSource)

  /** Graph with hidden nodes removed: ViewerGraph ~> ViewerGraph
    */
  val visibleGraph: Signal[ViewerGraph] =
    fullGraphV.signal
      .combineWith(hiddenNodes.signal)
      .map: (fullGraph, hiddenNodes) =>
        withLog("[fullGraphV -> visibleGraph]") {
          // no need to increment version as this is the visible graph, not the full one
          fullGraph
            .removeUnsupportedFeatures
            .removeNodes(hiddenNodes)
            .setDefaultTheme
        }
      .tapEach(_ => resetView())

  // -------------------------------
  // rendering
  // -------------------------------
  val visibleAST: Signal[DotAST] =
    visibleGraph.map(graph => withLog("[visibleGraph -> visibleAST]")(graphToDotAST(graph)))

  val visibleDOT: Signal[DotText] =
    visibleAST.map(ast => withLog("[visibleAST -> visibleDOT]")(ast.renderToDot))

end SourceFlow
