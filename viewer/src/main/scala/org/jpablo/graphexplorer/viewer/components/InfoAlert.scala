package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*

/** Displays the latest info message in a floating alert box (success style). */
def InfoAlert(infos: EventBus[String]): HtmlElement =
  val latest = Var[Option[String]](None)

  div(
    // Listen to the info bus and update state
    infos.events --> latest.writer.contramap[String](Some(_)),
    // Hide the alert after a delay
    latest.signal.changes
      .filter(_.isDefined)
      .flatMapSwitch(_ => EventStream.fromValue(None: Option[String], emitOnce = false).delay(2500)) --> latest,
    // Render only when there is a message
    // daisyUI toast: the corner-notification container — fixed positioning and
    // stacking come from the component instead of a hand-rolled absolute box.
    cls := "toast toast-end z-50",
    child.maybe <-- latest.signal.map:
      _.map: msg =>
        div(role := "alert", cls := "alert alert-success shadow-lg", span(msg))
  )

