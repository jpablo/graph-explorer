package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.Mods

/** Text actions, named by what they mean rather than by how they look.
  *
  * The app had four spellings for a text button and no rule connecting spelling to
  * importance, so weight ran backwards: `Expand` and `Collapse` -- utilities that
  * rearrange a list -- rendered filled with a border, heavier than `Show all`, which
  * changes what the diagram shows, and a dialog's `Cancel` competed with its `Ok`.
  *
  * Picking one of these is a judgement about consequence, which is a decision a call
  * site can actually make. `.tiny.primary` asks it to decide appearance instead, and
  * every call site answered separately. The daisyUI variant that expresses each role
  * lives here, once.
  *
  * See [[IconButton]] for the icon-only counterpart.
  */

/** The one action a surface exists for -- the thing you opened it to do. At most one per
  * surface: a second filled button makes both of them ordinary.
  */
def PrimaryAction(mods: Mods*): HtmlElement =
  Button(typ := "button", mods).tiny.primary

/** An ordinary action: available, not urgent. Tinted rather than outlined, so a row of
  * them reads as one group of options instead of several competing boxes.
  */
def Action(mods: Mods*): HtmlElement =
  Button(cls := "btn-soft", typ := "button", mods).tiny

/** An action that should read as text until you reach for it. Dismissals, and anything
  * standing beside a [[PrimaryAction]] that must not compete with it.
  */
def QuietAction(mods: Mods*): HtmlElement =
  Button(typ := "button", mods).tiny.ghost
