package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.components.SvgElementOps
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import upickle.default.*

trait ExportOps:
  this: ViewerState =>

  // NOTE: exports read the current value ONCE (xxxNow() shared handles). A
  // `finalSVG.foreach` here subscribed permanently, so every later render
  // silently re-copied the SVG over the user's clipboard.
  def copyAsFullDiagramSVG(): Unit =
    finalSVGNow().foreach(s => writeText(s.ref.outerHTML))

  def copySelectionAsSVG(): Unit =
    finalSVGNow().foreach: s =>
      writeText(SvgElementOps(s.ref).toSVGTextWithIds(selection.now(), selectionStrategyNow()))

  // DOT is an explicit export target, independent of the currently selected language.
  private def visibleDOT: String =
    viewerGraphToText(visibleGraphNow(), omitInternal = false)

  def copyAsDOT(): Unit =
    writeText(visibleDOT)

  def copyAsJSON(): Unit =
    val graph = visibleGraphNow()
    writeText(write(graph.elements))

  def printVisibleGraphToConsole(): Unit =
    val graph = visibleGraphNow()
    // Don't remove this line!! it IS the actual functionality
    pprint.log(graph, showFieldNames = true)
    dom.console.log("Visible graph printed to the console")

  def printVisibleGraphJsonToConsole(): Unit =
    val graph = visibleGraphNow()
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(write(graph.elements, indent = 2))
    dom.console.log("Visible graph printed to the console")

  def printVisibleDOTtoConsole(): Unit =
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(visibleDOT)
    dom.console.log("Visible DOT printed to the console")

  def printVisibleSimpleGraphJSONtoConsole(): Unit =
    // Debug helper: SimpleGraph is a Graphviz/VizJS-specific representation, so it is
    // built from the VISIBLE graph serialized as DOT (matching the command's name),
    // and a failure is reported instead of silently printing nothing.
    graphviz
      .textToSimpleGraph(visibleDOT)
      .fold(
        err => dom.console.error("Could not build SimpleGraph JSON:", err.getMessage),
        graph =>
          // Don't remove this line!! it IS the actual functionality
          dom.console.log(scalajs.js.JSON.parse(write(graph)))
          dom.console.log("Visible SimpleGraph JSON printed to the console")
      )
