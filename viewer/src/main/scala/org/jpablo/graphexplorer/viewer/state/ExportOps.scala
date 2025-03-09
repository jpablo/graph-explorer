package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.components.SvgElementOps
import upickle.default.*

trait ExportOps:
  this: ViewerState =>

  def copyAsFullDiagramSVG(): Unit =
    for html <- finalSVG.map(_.ref.outerHTML) do
      writeText(html)
  
  def copySelectionAsSVG(): Unit =
    for svgElem <- finalSVG do
      writeText(SvgElementOps(svgElem.ref).toSVGTextWithIds(selection.now()))
  
  def copyAsDOT(): Unit =
    for dot <- visibleDOT do
      writeText(dot.value)
  
  def copyAsJSON(): Unit =
    for ast <- sourceFlow.visibleAST do
      writeText(writeJs(ast).toString)
  
  def printVisibleGraphToConsole(): Unit =
    for graph <- visibleGraph do
      // Don't remove this line!! it IS the actual functionality
      pprint.log(graph, showFieldNames = false)
      dom.console.log("Visible graph printed to the console")
  
  def printVisibleDOTtoConsole(): Unit =
    for dotText <- visibleDOT do
      // Don't remove this line!! it IS the actual functionality
      dom.console.log(dotText.value)
      dom.console.log("Visible DOT printed to the console")
  
  def printVisibleJSONtoConsole(): Unit =
    for ast <- sourceFlow.visibleAST do
      // Don't remove this line!! it IS the actual functionality
      dom.console.log(write(ast, indent = 2))
      dom.console.log("Visible JSON DOT AST printed to the console")
