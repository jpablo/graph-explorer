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
import org.jpablo.graphexplorer.viewer.utils.Version

import scala.scalajs.js.Date

var lastDate: Date = null
var step = 0

enum Level:
  case Debug, Info, Warn, Error, None

import Level.*

def timeDelta() =
  if lastDate == null then
    lastDate = new Date()
  val currentDate = new Date()
  val delta = currentDate.getTime() - lastDate.getTime()
  lastDate = currentDate
  s"${delta / 1000.0} s,  at: ${currentDate.toISOString().split('T')(1)}"

inline def log[A](
    label:     String,
    resetStep: Boolean = false,
    level:     Level = None
)(body: => A): A =
  step = if resetStep then 1 else step + 1
  val numberedLabel = s"($step) $label"
  val fn = level match
    case Debug => dom.console.debug(_)
    case Info  => dom.console.info(_)
    case Warn  => dom.console.warn(_)
    case Error => dom.console.error(_)
    case None  => (_: Any) => ()
  fn(s"$numberedLabel [-->]: ${timeDelta()}")
  timeDelta()
  val a = body
  fn(s"$numberedLabel [<--]: ${timeDelta()}")
  a

class SourceFlow(
    initialSource: String,
    hiddenNodes:   Signal[Set[NodeId]],
    resetView:     () => Unit
)(using Owner):

  val fullGraphV: Var[ViewerGraph] = Var(ViewerGraph.empty)
  val fullGraph = fullGraphV.signal

  case class SourceTextAndAST(source: String, ast: DotAST, version: Version)

  private val sourceTextAndAST = Var(SourceTextAndAST("", DotAST.empty, 0))

  /** parse source on write: String ~> DotAST
    */
  val sourceText: Var[String] =
    sourceTextAndAST.zoom({ (a1: SourceTextAndAST) =>
      log("[sourceTextAndAST -> sourceText] SourceTextAndAST => String")(a1.source)
    }) { (value, newSource) =>
      log("[sourceText -> sourceTextAndAST] (SourceTextAndAST, String) => SourceTextAndAST") {
        if newSource == value.source then
          value
        else
          dom.console.debug(s"newSource != value.source, parsing doc of length ${newSource.length}")
          // at this point we have a new source, so we increment the version.
          val nextVersion = value.version + 1
          dom.console.debug(s"sourceText: ${value.version} -> ${nextVersion}")
          val newAST =
            DotText(newSource, nextVersion).parseAST
              .headOption
              .getOrElse(DotAST.empty)
              .attachInternalAttributes
          SourceTextAndAST(newSource, newAST, nextVersion)
      }
    }

  /** render AST on write: DotAST ~> String
    */
  private val sourceAST: Var[DotAST] =
    sourceTextAndAST.zoom({ (newSourceAndTextAST: SourceTextAndAST) =>
      // TODO: it would be better not to trigger an event if the AST is the same
      log("[sourceTextAndAST -> sourceAST] SourceTextAndAST => DotAST")(newSourceAndTextAST.ast)
    }): (textAndAST, newAST: DotAST) =>
      log("[sourceAST -> sourceTextAndAST] (SourceTextAndAST, DotAST) => SourceTextAndAST") {
        SourceTextAndAST(newAST.optimize.render(keepInternal = false), newAST, textAndAST.version)
      }

  sourceAST.signal.foreach { (sourceAST: DotAST) =>
    dom.console.debug(
      "[sourceAST -> fullGraphV] sourceAST => ViewerGraph",
      s"sourceAST.version: ${sourceAST.version}",
      s"fullGraphV.version: ${fullGraphV.now().version}"
    )
    val graph = sourceAST.toViewerGraph
    if fullGraphV.now() == graph || sourceAST.version <= fullGraphV.now().version then
      dom.console.debug(s"fullGraphV.now() == graph, not updating")
    else
      log("[fullGraphV] sourceAST => ViewerGraph"):
        fullGraphV.set(graph)
  }

  fullGraphV.signal.foreach { graph =>
    log(s"[fullGraphV -> sourceAST:1] scheduling... (v: ${graph.version})", resetStep = true):
      // whoever modified the graph should increment the version, so we don't need to do it here
      val ast = graphToDotAST(graph)

      dom.console.assert(ast.id.isDefined, "AST id is not defined")
      if sourceAST.now() != ast then
        // async update
        dom.window.setTimeout(
          { () =>
            dom.console.debug(
              s"[fullGraphV -> sourceAST:2] handler (sourceAST.set, v: ${graph.version}) ${timeDelta()}"
            )
            log("[fullGraphV -> sourceAST:2] graphToDotAST: ViewerGraph => DotAST"):
              sourceAST.set(ast)
          },
          1000
        )
  }

  dom.console.debug(s"setting initialSource: $initialSource")
  sourceText.set(initialSource)

  /** Graph with hidden nodes removed: ViewerGraph ~> ViewerGraph
    */
  val visibleGraph: Signal[ViewerGraph] =
    fullGraphV.signal
      .combineWith(hiddenNodes.signal)
      .map: (fullGraph, hiddenNodes) =>
        log("[fullGraphV -> visibleGraph] (.removeUnsupportedFeatures.removeNodes)") {
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
    visibleGraph.map(graph =>
      log("[visibleGraph -> visibleAST] graphToDotAST: ViewerGraph => DotAST")(graphToDotAST(graph))
    )

  val visibleDOT: Signal[DotText] =
    visibleAST.map(ast =>
      log("[visibleAST -> visibleDOT] renderToDot: DotAST => DotText")(ast.renderToDot)
    )

end SourceFlow
