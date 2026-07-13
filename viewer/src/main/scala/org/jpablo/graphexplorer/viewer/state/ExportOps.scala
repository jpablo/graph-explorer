package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.components.SvgElementOps
import upickle.default.*

trait ExportOps:
  this: ViewerState =>

  def copyAsFullDiagramSVG(): Unit =
    finalSVG.foreach(_.foreach(s => writeText(s.ref.outerHTML)))

  def copySelectionAsSVG(): Unit =
    finalSVG.foreach(_.foreach { s =>
      writeText(SvgElementOps(s.ref).toSVGTextWithIds(selection.now(), selectionStrategy.observe.now()))
    })

  def copyAsDOT(): Unit =
    val dot = visibleDOT.observe.now()
    writeText(dot.value)

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
    val dotText = visibleDOT.observe.now()
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(dotText.value)
    dom.console.log("Visible DOT printed to the console")

  def printVisibleSimpleGraphJSONtoConsole(): Unit =
    // TODO: to implement this we need to keep the SimpleGraph in the ViewerState
    val graph = phases.simpleGraph.observe.now()
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(scalajs.js.JSON.parse(write(graph)))
    dom.console.log("Visible graph JSON printed to the console")
