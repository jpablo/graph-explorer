package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.typings.{SimpleGraph, SimpleGraphConverter}
import org.jpablo.graphexplorer.viewer.components.SvgElementOps
import org.jpablo.graphexplorer.viewer.graph.ExportViewerGraphElements
import upickle.default.*

trait ExportOps:
  this: ViewerState =>

  def copyAsFullDiagramSVG(): Unit =
    finalSVG.foreach(_.foreach(s => writeText(s.ref.outerHTML)))

  def copySelectionAsSVG(): Unit =
    finalSVG.foreach(_.foreach(s => writeText(SvgElementOps(s.ref).toSVGTextWithIds(selection.now()))))

  def copyAsDOT(): Unit =
    val dot = visibleDOT.observe.now()
    writeText(dot.value)

  def copyAsJSON(): Unit =
    val graph = visibleGraph.observe.now()
    val ast = SimpleGraphConverter.fromViewerGraphElements(graph.elements)
    writeText(write(ast))

  def printVisibleGraphToConsole(): Unit =
    val graph = visibleGraph.observe.now()
    // Don't remove this line!! it IS the actual functionality
    pprint.log(graph, showFieldNames = true)
    dom.console.log("Visible graph printed to the console")

  def printVisibleGraphJsonToConsole(): Unit =
    val graph = visibleGraph.observe.now()
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(write(ExportViewerGraphElements.fromViewerGraphElements(graph.elements), indent = 2))
    dom.console.log("Visible graph printed to the console")

  def printVisibleDOTtoConsole(): Unit =
    val dotText = visibleDOT.observe.now()
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(dotText.value)
    dom.console.log("Visible DOT printed to the console")

  def printVisibleJSONtoConsole(): Unit =
    val graph = visibleGraph.observe.now()
    val ast = SimpleGraphConverter.fromViewerGraphElements(graph.elements)
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(scalajs.js.JSON.parse(write(ast)))
    dom.console.log("Visible JSON VizJS Graph printed to the console")
