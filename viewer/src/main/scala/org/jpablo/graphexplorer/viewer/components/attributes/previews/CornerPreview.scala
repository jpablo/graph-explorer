package org.jpablo.graphexplorer.viewer.components.attributes.previews

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.CornerStyle

def CornerPreview(corner: CornerStyle): Option[() => SvgElement] =
  corner match
    case CornerStyle.rounded   => Some(() => roundedIcon())
    case CornerStyle.diagonals => Some(() => diagonalsIcon())
    case CornerStyle.normal    => Some(() => normalIcon())

def roundedIcon(width: String = "17px", height: String = "17px") =
  svg.svg(
    svg.width    := width,
    svg.height   := height,
    svg.viewBox  := "0 0 17 17",
    svg.xmlns    := "http://www.w3.org/2000/svg",
    svg.xmlSpace := "preserve",
    svg.style    := "fill-rule:evenodd;clip-rule:evenodd;",
    svg.g(
      svg.idAttr    := "Artboard1",
      svg.transform := "matrix(0.839365,0,0,0.879267,-242.524,-155.023)",
      svg.rect(
        svg.x      := "288.938",
        svg.y      := "176.309",
        svg.width  := "19.362",
        svg.height := "18.775",
        svg.style  := "fill:none;"
      ),

      // svg.clipPath(
      //   svg.idAttr := "_clip1",
      //   svg.rect(
      //     svg.x := "288.938",
      //     svg.y := "176.309",
      //     svg.width := "19.362",
      //     svg.height := "18.775"
      //   )
      // ),

      svg.g(
        svg.clipPathAttr := "url(#_clip1)",
        svg.g(
          svg.idAttr    := "rounded",
          svg.transform := "matrix(0.502333,0,0,0.479536,289.683,194.364)",
          svg.path(
            svg.d := "M24,-36L12,-36C6,-36 0,-30 0,-24L0,-12C0,-6 6,0 12,0L24,0C30,0 36,-6 36,-12L36,-24C36,-30 30,-36 24,-36",
            svg.style := "fill:none;fill-rule:nonzero;stroke:black;stroke-width:2.31px;"
          )
        )
      )
    )
  )

def diagonalsIcon(width: String = "16px", height: String = "16px") =
  svg.svg(
    svg.width    := width,
    svg.height   := height,
    svg.viewBox  := "0 0 16 16",
    svg.xmlns    := "http://www.w3.org/2000/svg",
    svg.xmlSpace := "preserve",
    svg.style    := "fill-rule:evenodd;clip-rule:evenodd;",
    svg.g(
      svg.transform := "matrix(1,0,0,1,-0.134642,-59.8182)",
      svg.g(
        svg.idAttr    := "Artboard3",
        svg.transform := "matrix(0.930408,0,0,0.916944,-0.737462,5.28462)",
        svg.rect(
          svg.x      := "0.937",
          svg.y      := "59.473",
          svg.width  := "17.494",
          svg.height := "17.656",
          svg.style  := "fill:none;"
        ),
//          svg.clipPath(
//            svg.idAttr := "_clip1",
//            svg.rect(svg.x := "0.937", svg.y := "59.473", svg.width := "17.494", svg.height := "17.656")
//          ),
        svg.g(
          svg.clipPathAttr := "url(#_clip1)",
          svg.g(
            svg.idAttr    := "corners",
            svg.transform := "matrix(0.967317,0,0,0.981522,-2.99634,58.8758)",
            svg.g(
              svg.idAttr    := "normal",
              svg.transform := "matrix(0.472222,0,0,0.472222,-20.8526,18.1549)",
              svg.rect(
                svg.x      := "54",
                svg.y      := "-36",
                svg.width  := "36",
                svg.height := "36",
                svg.style  := "fill:none;fill-rule:nonzero;stroke:black;stroke-width:1.94px;"
              )
            ),
            svg.g(
              svg.transform := "matrix(0.476497,0,0,0.476497,-46.9046,18.3038)",
              svg.path(
                svg.d     := "M120,-36L108,-24",
                svg.style := "fill:none;fill-rule:nonzero;stroke:black;stroke-width:1.94px;"
              )
            ),
            svg.g(
              svg.transform := "matrix(0.476497,0,0,0.476497,-46.9046,18.3038)",
              svg.path(
                svg.d     := "M108,-12L120,0",
                svg.style := "fill:none;fill-rule:nonzero;stroke:black;stroke-width:1.94px;"
              )
            ),
            svg.g(
              svg.transform := "matrix(0.476497,0,0,0.476497,-47.0457,18.2921)",
              svg.path(
                svg.d     := "M132,0L144,-12",
                svg.style := "fill:none;fill-rule:nonzero;stroke:black;stroke-width:1.94px;"
              )
            ),
            svg.g(
              svg.transform := "matrix(0.476497,0,0,0.476497,-47.0457,18.2921)",
              svg.path(
                svg.d     := "M144,-24L132,-36",
                svg.style := "fill:none;fill-rule:nonzero;stroke:black;stroke-width:1.94px;"
              )
            )
          )
        )
      )
    )
  )

def normalIcon(width: String = "17px", height: String = "17px"): SvgElement = {
  svg.svg(
    svg.width    := width,
    svg.height   := height,
    svg.viewBox  := "0 0 17 17",
    svg.xmlns    := "http://www.w3.org/2000/svg",
    svg.xmlSpace := "preserve",
    svg.style    := "fill-rule:evenodd;clip-rule:evenodd;",
    svg.g(
      svg.transform := "matrix(1,0,0,1,-0.0468048,-28.177)",
      svg.g(
        svg.idAttr    := "Artboard2",
        svg.transform := "matrix(0.910709,0,0,0.90113,0.134121,3.03773)",
        svg.rect(
          svg.x      := "-0.096",
          svg.y      := "27.898",
          svg.width  := "17.742",
          svg.height := "18.103",
          svg.style  := "fill:none;"
        ),

        // svg.clipPath(
        //   svg.idAttr := "_clip1",
        //   svg.rect(
        //     svg.x := "-0.096",
        //     svg.y := "27.898",
        //     svg.width := "17.742",
        //     svg.height := "18.103"
        //   )
        // ),

        svg.g(
          svg.clipPathAttr := "url(#_clip1)",
          svg.g(
            svg.idAttr    := "normal",
            svg.transform := "matrix(0.466669,0,0,0.47163,-24.7424,45.4565)",
            svg.rect(
              svg.x      := "54",
              svg.y      := "-36",
              svg.width  := "36",
              svg.height := "36",
              svg.style  := "fill:none;fill-rule:nonzero;stroke:black;stroke-width:1.94px;"
            )
          )
        )
      )
    )
  )
}
