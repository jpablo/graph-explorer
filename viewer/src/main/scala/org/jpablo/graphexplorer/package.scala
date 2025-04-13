package org.jpablo

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.{ReactiveHtmlElement, ReactiveSvgElement}
import org.jpablo.graphexplorer.viewer.models.Lens

package object graphexplorer:
  type Mods = Modifier[ReactiveHtmlElement.Base]
  type SvgMods = Modifier[ReactiveSvgElement.Base]

  extension [A] (va: Var[A])
    def zoomLens[B](lens: Lens[A, B]): Var[B] =
      va.zoomLazy(lens.get)(lens.update)
