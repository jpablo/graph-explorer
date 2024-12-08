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
    ignore:    Boolean = false,
    resetStep: Boolean = false,
    level:     Level = Debug
)(body: => A): A =
  step = if resetStep then 1 else step + 1
  val numberedLabel = s"($step) $label"
  val fn = level match
    case Debug => dom.console.debug(_)
    case Info  => dom.console.info(_)
    case Warn  => dom.console.warn(_)
    case Error => dom.console.error(_)
    case None  => (_: Any) => ()
  if !ignore then
//    dom.console.group(s"($step) $label")
    fn(s"$numberedLabel [-->]: ${timeDelta()}")
//  dom.console.count(label)
//  dom.console.time(label)
  timeDelta()
  val a = body
//  dom.console.timeEnd(label)
//  if !ignore then
//    dom.console.debug(s"$numberedLabel [<--]: ${timeDelta()}")
//    dom.console.groupEnd()
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
//      pprint.log(a1)
      log("[sourceTextAndAST -> sourceText] SourceTextAndAST => String")(a1.source)
    }) { (value, newSource) =>
//      pprint.log(value)
      log("[sourceText -> sourceTextAndAST] (SourceTextAndAST, String) => SourceTextAndAST", ignore = false) {
        if newSource == value.source then
          value
        else
          dom.console.log(s"newSource != value.source, parsing doc of length ${newSource.length}")
          // at this point we have a new source, so we increment the version.
          val nextVersion = value.version + 1
          dom.console.log(s"sourceText: ${value.version} -> ${nextVersion}")
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
//      pprint.log(newSourceAndTextAST)
      log("[sourceTextAndAST -> sourceAST] SourceTextAndAST => DotAST")(newSourceAndTextAST.ast)
    }): (textAndAST, newAST: DotAST) =>
//      pprint.log(newAST)
      log("[sourceAST -> sourceTextAndAST] (SourceTextAndAST, DotAST) => SourceTextAndAST", ignore = false) {
        // sourceAST is updated by fullGraphV, so we don't need to increment the version here
        SourceTextAndAST(newAST.optimize.render(keepInternal = false), newAST, textAndAST.version)
      }

  sourceAST.signal.foreach { (sourceAST: DotAST) =>
    dom.console.warn(
      "[sourceAST -> fullGraphV] sourceAST => ViewerGraph",
      s"sourceAST.version: ${sourceAST.version}",
      s"fullGraphV.version: ${fullGraphV.now().version}"
    )
//    pprint.log(sourceAST)
    val graph = sourceAST.toViewerGraph
//    pprint.log(fullGraphV.now())
//    pprint.log(graph)
    if fullGraphV.now() == graph || sourceAST.version <= fullGraphV.now().version then
      dom.console.warn(s"fullGraphV.now() == graph, not updating")
    else
      log("[fullGraphV] sourceAST => ViewerGraph", ignore = false):
        fullGraphV.set(graph)
  }

  fullGraphV.signal.foreach { graph =>
    log(s"[fullGraphV -> sourceAST:1] scheduling... (v: ${graph.version})", ignore = false, resetStep = true, level = Warn):
      // whoever modified the graph should increment the version, so we don't need to do it here
      val ast = graphToDotAST(graph)

//      pprint.log(graph)
//      pprint.log(ast)
      dom.console.assert(ast.id.isDefined, "AST id is not defined")
      if sourceAST.now() != ast then
        // async update
        dom.window.setTimeout(
          { () =>
            dom.console.error(
              s"[fullGraphV -> sourceAST:2] handler (sourceAST.set, v: ${graph.version}) ${timeDelta()}"
            )
            log("[fullGraphV -> sourceAST:2] graphToDotAST: ViewerGraph => DotAST", ignore = false):
              sourceAST.set(ast)
          },
          1000
        )
  }

  // initial setup
  dom.console.log(s"setting initialSource")
  sourceText.set(initialSource)

  /** Graph with hidden nodes removed: ViewerGraph ~> ViewerGraph
    */
  val visibleGraph: Signal[ViewerGraph] =
    fullGraphV.signal
      .combineWith(hiddenNodes.signal)
      .map: (fullGraph, hiddenNodes) =>
        log("[fullGraphV -> visibleGraph] (.removeUnsupportedFeatures.removeNodes)", ignore = false) {
          // no need to increment version as this is the visible graph, not the full one
          fullGraph
            .removeUnsupportedFeatures
            .removeNodes(hiddenNodes)
            .setDefaultTheme
        }
      .tapEach(_ => resetView())
      .tapEach(g => dom.console.log(s"[visibleGraph] version: ${g.version}"))

  // -------------------------------
  // rendering
  // -------------------------------
  val visibleAST: Signal[DotAST] =
    visibleGraph.map(graph =>
      log("[visibleGraph -> visibleAST] graphToDotAST: ViewerGraph => DotAST", ignore = false)(graphToDotAST(graph))
    ).tapEach(ast => dom.console.log(s"[visibleAST] version: ${ast.version}"))

  val visibleDOT: Signal[DotText] =
    visibleAST.map(ast =>
      log("[visibleAST -> visibleDOT] renderToDot: DotAST => DotText", ignore = false)(ast.renderToDot)
    )

end SourceFlow
