package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import org.jpablo.graphexplorer.Mods

/** The chrome's icon control: a square carrying an icon, a tooltip, and nothing else.
  *
  * Every bar in the app draws its icon controls with these, so a button means the same thing
  * wherever it sits. The old spelling was daisyUI's `btn btn-xs btn-ghost`, which measures
  * 34x24 with lopsided 0/8px padding and a transparent 1px border against `.gx-icon-btn`'s
  * even 24x24 — the reason the top bar and the side toolbars never lined up.
  *
  * Tooltips carry the label, so the icon has to be legible on its own; `aria.label` gives
  * the same words to anyone who cannot see it.
  */
def IconButton(icon: String, tip: String, tipPos: String = "tooltip-bottom", mods: Mods*)(action: => Unit): Div =
  iconShell(tip, tipPos, button(typ := "button", i(cls := icon), onClick --> action), mods)

/** An icon control that stays visibly pressed while its flag is on. */
def IconToggle(icon: String, tip: String, flag: Var[Boolean], tipPos: String = "tooltip-bottom", mods: Mods*): Div =
  iconShell(
    tip,
    tipPos,
    button(typ := "button", cls("active") <-- flag.signal, i(cls := icon), onClick --> flag.update(!_)),
    mods
  )

/** [[IconButton]] with a native `title` in place of the tooltip bubble.
  *
  * For icons inside a container that scrolls: daisyUI's tooltip renders its bubble as a
  * child pseudo-element, and a scrolling ancestor counts that bubble's full width as
  * content. In the breadcrumbs (`overflow-x: auto`) an 18px pencil reported 56px of scroll
  * width and raised a scrollbar under the diagram title.
  */
def IconButtonTitled(icon: String, tip: String, mods: Mods*)(action: => Unit): Button =
  button(
    cls        := "gx-icon-btn",
    typ        := "button",
    title      := tip,
    aria.label := tip,
    i(cls := icon),
    onClick --> action,
    mods
  )

/** An icon control that leaves the app. An anchor rather than a button, so middle-click and
  * "open in new tab" behave the way they do everywhere else on the web.
  */
def IconLink(icon: String, tip: String, url: String, tipPos: String = "tooltip-bottom", mods: Mods*): Div =
  iconShell(tip, tipPos, a(href := url, target := "_blank", i(cls := icon)), mods)

private def iconShell(tip: String, tipPos: String, control: HtmlElement, mods: Seq[Mods]): Div =
  Tooltip(
    text = tip,
    cls := tipPos,
    control.amend(cls := "gx-icon-btn", aria.label := tip, mods)
  )
