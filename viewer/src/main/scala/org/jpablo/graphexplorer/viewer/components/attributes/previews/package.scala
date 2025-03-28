package org.jpablo.graphexplorer.viewer.components.attributes

import com.raquo.laminar.api.L.*
import org.scalajs.dom.{DOMParser, MIMEType, SVGSVGElement}

package object previews:
  def parseSVG(svgString: String) =
    val parser = new DOMParser
    val doc    = parser.parseFromString(svgString, MIMEType.`image/svg+xml`)
    val svgElem = doc.documentElement.asInstanceOf[SVGSVGElement]
    foreignSvgElement(svg.svg, svgElem)
