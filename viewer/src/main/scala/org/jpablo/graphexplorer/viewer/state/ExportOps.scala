package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidBackend
import org.jpablo.graphexplorer.viewer.components.SvgElementOps
import org.jpablo.graphexplorer.viewer.components.svgCanvas.DiagramColors
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph.viewerGraphToText
import upickle.default.*

trait ExportOps:
  this: ViewerState =>

  // NOTE: exports read the current value ONCE (xxxNow() shared handles). A
  // `finalSVG.foreach` here subscribed permanently, so every later render
  // silently re-copied the SVG over the user's clipboard.
  def copyAsFullDiagramSVG(): Unit =
    finalSVGNow().foreach(s => writeText(fullDiagramSVGText(s.ref)))

  /** The text of the full diagram for an export. It follows the setting for
    * the sheet behind a Mermaid drawing: with the setting off, the text has no
    * sheet. A selection export has no sheet, because the sheet is not a
    * selectable element. The text has the original colors, also on a dark
    * theme.
    */
  private def fullDiagramSVGText(svg: dom.svg.SVG): String =
    MermaidBackend.exportText(DiagramColors.lightCopy(svg), keepSheet = mermaidSheet.now())

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

  /** The editor's text verbatim, in whatever language is selected — unlike copyAsDOT, which
    * re-serializes the VISIBLE graph and always emits DOT. What you see in the panel is what
    * lands on the clipboard, hidden elements and formatting included.
    */
  def copySourceText(): Unit =
    writeText(sourceText.now())

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
