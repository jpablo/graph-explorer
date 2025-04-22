package org.jpablo.graphexplorer.viewer.domUtils

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.scalajs.dom.{DOMParser, MIMEType}

def parseSVG(svgString: String): ReactiveSvgElement[dom.svg.SVG] =
  val parser  = new DOMParser
  val doc     = parser.parseFromString(svgString, MIMEType.`image/svg+xml`)
  val svgElem = doc.documentElement.asInstanceOf[dom.svg.SVG]
  foreignSvgElement(svg.svg, svgElem)
