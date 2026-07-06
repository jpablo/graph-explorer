package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.components.SvgElementOps
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import upickle.default.*

trait ExportOps:
  this: ViewerState =>

  def copyAsFullDiagramSVG(): Unit =
    finalSVG.foreach(_.foreach(s => writeText(s.ref.outerHTML)))

  def copySelectionAsSVG(): Unit =
    finalSVG.foreach(_.foreach { s =>
      writeText(SvgElementOps(s.ref).toSVGTextWithIds(selection.now(), selectionStrategy.observe.now()))
    })

  // DOT is an explicit export target, independent of the currently selected language.
  private def visibleDOT: String =
    viewerGraphToText(visibleGraph.observe.now(), omitInternal = false)

  def copyAsDOT(): Unit =
    writeText(visibleDOT)

  def copyAsJSON(): Unit =
    val graph = visibleGraph.observe.now()
    writeText(write(graph.elements))

  def printVisibleGraphToConsole(): Unit =
    val graph = visibleGraph.observe.now()
    // Don't remove this line!! it IS the actual functionality
    pprint.log(graph, showFieldNames = true)
    dom.console.log("Visible graph printed to the console")

  def printVisibleGraphJsonToConsole(): Unit =
    val graph = visibleGraph.observe.now()
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(write(graph.elements, indent = 2))
    dom.console.log("Visible graph printed to the console")

  def printVisibleDOTtoConsole(): Unit =
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(visibleDOT)
    dom.console.log("Visible DOT printed to the console")

  def printVisibleSimpleGraphJSONtoConsole(): Unit =
    // Debug helper: SimpleGraph is a Graphviz/VizJS-specific representation, so this is DOT-only.
    graphviz.textToSimpleGraph(sourceText.now()).foreach { graph =>
      // Don't remove this line!! it IS the actual functionality
      dom.console.log(scalajs.js.JSON.parse(write(graph)))
      dom.console.log("Visible JSON VizJS Graph printed to the console")
    }
