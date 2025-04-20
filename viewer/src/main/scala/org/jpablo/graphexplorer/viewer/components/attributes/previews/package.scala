package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.scalajs.dom.{DOMParser, MIMEType}

package object previews:
  def parseSVG(svgString: String) =
    val parser = new DOMParser
    val doc    = parser.parseFromString(svgString, MIMEType.`image/svg+xml`)
    val svgElem = doc.documentElement.asInstanceOf[dom.svg.SVG]
    foreignSvgElement(svg.svg, svgElem)
