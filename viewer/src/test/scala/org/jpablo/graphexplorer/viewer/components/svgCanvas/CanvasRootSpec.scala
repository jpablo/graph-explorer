package org.jpablo.graphexplorer.viewer.components.svgCanvas

import munit.FunSuite
import org.scalajs.dom

class CanvasRootSpec extends FunSuite:

  private def parse(svgText: String): dom.svg.SVG =
    dom.DOMParser()
      .parseFromString(svgText, dom.MIMEType.`image/svg+xml`)
      .documentElement
      .asInstanceOf[dom.svg.SVG]

  test("all root-level Mermaid sequence drawing elements share the canvas transform group"):
    val svg = parse(
      """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
        |  <g id="actor-a"><rect/></g>
        |  <style>.messageText { fill: black; }</style>
        |  <defs><marker id="arrow"/></defs>
        |  <text class="messageText">hello</text>
        |  <line class="messageLine0"/>
        |</svg>""".stripMargin
    )

    val mainGroup = CanvasRoot.mainGroup(svg)

    assertEquals(mainGroup.parentNode, svg)
    assertEquals(mainGroup.querySelectorAll("g#actor-a, text.messageText, line.messageLine0").length, 3)
    assertEquals(svg.querySelectorAll(":scope > style, :scope > defs").length, 2)
    assertEquals(svg.querySelectorAll(":scope > text, :scope > line").length, 0)

  test("a Graphviz-style single drawing group remains the canvas transform group"):
    val svg = parse(
      """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
        |  <g id="graph0" transform="translate(4 96)"><rect/></g>
        |</svg>""".stripMargin
    )

    val graph0 = svg.querySelector("g#graph0")

    assertEquals(CanvasRoot.mainGroup(svg), graph0)
    assertEquals(svg.children.length, 1)

  test("the established canvas group survives later overlay siblings"):
    val svg = parse(
      """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
        |  <g id="graph0"><rect/></g>
        |</svg>""".stripMargin
    )
    val graph0  = CanvasRoot.mainGroup(svg)
    val overlay = svg.ownerDocument.createElementNS("http://www.w3.org/2000/svg", "rect")
    overlay.setAttribute("id", "selection-rectangle")
    svg.appendChild(overlay)

    assertEquals(CanvasRoot.mainGroup(svg), graph0)
    assertEquals(svg.querySelectorAll(":scope > g").length, 1)
