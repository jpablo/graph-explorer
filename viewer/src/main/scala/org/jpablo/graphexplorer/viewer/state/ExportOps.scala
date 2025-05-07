package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.components.SvgElementOps
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
    val ast = phases.visibleAST.observe.now()
    writeText(writeJs(ast).toString)

  def printVisibleGraphToConsole(): Unit =
    val graph = visibleGraph.observe.now()
    // Don't remove this line!! it IS the actual functionality
    pprint.log(graph, showFieldNames = true)
    dom.console.log("Visible graph printed to the console")

  def printVisibleDOTtoConsole(): Unit =
    val dotText = visibleDOT.observe.now()
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(dotText.value)
    dom.console.log("Visible DOT printed to the console")

  def printVisibleJSONtoConsole(): Unit =
    val ast = phases.visibleAST.observe.now()
    // Don't remove this line!! it IS the actual functionality
    dom.console.log(write(ast, indent = 2))
    dom.console.log("Visible JSON DOT AST printed to the console")
