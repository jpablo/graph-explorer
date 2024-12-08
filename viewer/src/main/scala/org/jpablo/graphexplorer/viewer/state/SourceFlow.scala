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

import scala.scalajs.js.Date

var lastDate: Date = null
var step = 0

val useDebug = false

def timeDelta() =
  if lastDate == null then
    lastDate = new Date()
  val currentDate = new Date()
  val delta = currentDate.getTime() - lastDate.getTime()
  lastDate = currentDate
  s"${delta / 1000.0} s,  at: ${currentDate.toISOString().split('T')(1)}"

inline def log[A](label: String, ignore: Boolean = false, resetStep: Boolean = false)(body: => A): A =
  step = if resetStep then 1 else step + 1
  val numberedLabel = s"($step) $label"
//  if !ignore then
//    dom.console.group(s"($step) $label")
//    dom.console.debug(s"$numberedLabel [-->]: ${timeDelta()}")
//  dom.console.count(label)
//  dom.console.time(label)
  timeDelta()
  val a = body
//  dom.console.timeEnd(label)
  if !ignore then
    dom.console.debug(s"$numberedLabel [<--]: ${timeDelta()}")
//    dom.console.groupEnd()
  a

class SourceFlow(
    initialSource: String,
    hiddenNodes:   Signal[Set[NodeId]],
    resetView:     () => Unit
)(using Owner):

  case class SourceTextAndAST(source: String, ast: DotAST)

  private val sourceTextAndAST = Var(SourceTextAndAST("", DotAST.empty))

  /** render AST on write: DotAST ~> String
    */
  private val sourceAST: Var[DotAST] =
    sourceTextAndAST.zoom({ (a1: SourceTextAndAST) =>
      log("[sourceAST] SourceTextAndAST => DotAST")(a1.ast)
    }): (_, newAST: DotAST) =>
      log("[sourceAST] (SourceTextAndAST, DotAST) => SourceTextAndAST", ignore = false) {
        SourceTextAndAST(newAST.optimize.render(keepInternal = false), newAST)
      }

  /** parse source on write: String ~> DotAST
    */
  val sourceText: Var[String] =
    sourceTextAndAST.zoom({ (a1: SourceTextAndAST) =>
      log("[sourceText] SourceTextAndAST => String")(a1.source)
    }) { (value, newSource) =>
      log("[sourceText] (SourceTextAndAST, String) => SourceTextAndAST", ignore = false) {
        if newSource == value.source then
//          dom.console.log(s"newSource == value.source")
          value
        else
          dom.console.log(s"newSource != value.source, parsing doc of length ${newSource.length}")
          val newAST =
            DotText(newSource).parseAST
              .headOption
              .getOrElse(DotAST.empty)
              .attachInternalAttributes
          SourceTextAndAST(newSource, newAST)
      }
    }

  // initial setup
  dom.console.log(s"setting initialSource")
  sourceText.set(initialSource)

  val fullGraphV: Var[ViewerGraph] = Var(ViewerGraph.empty)

  /** DotAST ~> ViewerGraph
    *
    * Arrows are assigned consecutive ids starting from 1
    */
//  val fullGraphV: Var[ViewerGraph] =
//    sourceAST.zoom({ t =>
//      log("[fullGraphV] toViewerGraph: DotAST => ViewerGraph", ignore = false) {
//        t.toViewerGraph
//      }
//    }) { (_, newGraph) =>
//      log("[fullGraphV] graphToDotAST: (DotAST, ViewerGraph) => DotAST", ignore = false, resetStep = true) {
//        graphToDotAST(newGraph)
//      }
//    }

  val fullGraph: Signal[ViewerGraph] =
    fullGraphV.signal

  /** Graph with hidden nodes removed: ViewerGraph ~> ViewerGraph
    */
  val visibleGraph: Signal[ViewerGraph] =
    fullGraph
      .combineWith(hiddenNodes.signal)
      .map: (fullGraph, hiddenNodes) =>
        log("[visibleGraph] (.removeUnsupportedFeatures.removeNodes)", ignore = false) {
          fullGraph.removeUnsupportedFeatures.removeNodes(hiddenNodes).setDefaultTheme
        }
      .tapEach(_ => resetView())

  // -------------------------------
  // rendering
  // -------------------------------
  val visibleAST: Signal[DotAST] =
    visibleGraph.map(graph =>
      log("[visibleAST] graphToDotAST: ViewerGraph => DotAST", ignore = false)(graphToDotAST(graph))
    )

  val visibleDOT: Signal[DotText] =
    visibleAST.map(ast => log("[visibleDOT] renderToDot: DotAST => DotText", ignore = false)(ast.renderToDot))

  sourceAST.signal.foreach { ast =>
      val graph = ast.toViewerGraph
      if fullGraphV.now() != graph then
        log("[fullGraphV] sourceAST => ViewerGraph", ignore = false):
          fullGraphV.set(graph)
  }

  fullGraph.foreach { graph =>
    log("[fullGraphV:1] ... ", ignore = false, resetStep = true):
      val ast = graphToDotAST(graph)
      if sourceAST.now() != ast then
        // async update
        dom.window.setTimeout(
          { () =>
            dom.console.error(s"[fullGraphV:2] handler ${timeDelta()}")
            log("[fullGraphV:2] graphToDotAST: ViewerGraph => DotAST", ignore = false):
              sourceAST.set(ast)
          },
          1000
        )
  }

end SourceFlow
