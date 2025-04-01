package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.components.SvgElementOps
import upickle.default.*

trait ExportOps:
  this: ViewerState =>

  def copyAsFullDiagramSVG(): Unit =
    val html = finalSVG.map(_.ref.outerHTML).observe().now()
    writeText(html)

  def copySelectionAsSVG(): Unit =
    val svgElem = finalSVG.observe().now()
    writeText(SvgElementOps(svgElem.ref).toSVGTextWithIds(selection.now()))

  def copyAsDOT(): Unit =
    val dot = visibleDOT.observe().now()
    writeText(dot.value)

  def copyAsJSON(): Unit =
    val ast = sourceFlow.visibleAST.observe().now()
    writeText(writeJs(ast).toString)

  def printVisibleGraphToConsole(): Unit =
    val graph = visibleGraph.observe().now()
    // Don't remove this line!! it IS the actual functionality
    pprint.log(graph, showFieldNames = true)
    dom.console.log("Visible graph printed to the console")

  def printVisibleDOTtoConsole(): Unit =
    val dotText = visibleDOT.observe().now()
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(dotText.value)
    dom.console.log("Visible DOT printed to the console")

  def printVisibleJSONtoConsole(): Unit =
    val ast = sourceFlow.visibleAST.observe().now()
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(write(ast, indent = 2))
    dom.console.log("Visible JSON DOT AST printed to the console")
