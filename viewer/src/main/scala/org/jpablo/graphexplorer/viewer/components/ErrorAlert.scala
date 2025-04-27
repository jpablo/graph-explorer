package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*

/** Displays the latest error message in a floating alert box. */
def ErrorAlert(errors: EventBus[String]): HtmlElement =
  val latestError = Var[Option[String]](None)

  div(
    // Listen to the error bus and update the latestError state
    errors.events --> latestError.writer.contramap[String](Some(_)),
    // Hide the alert after a delay when an error is set
    latestError.signal.changes
      .filter(_.isDefined)
      .flatMapSwitch(_ =>
        EventStream.fromValue(None: Option[String], emitOnce = false)
          .delay(5000)
      ) --> latestError,
    // Render the alert only when there is an error
    cls := "absolute bottom-4 right-4 z-50",
    child.maybe <-- latestError.signal.map:
      _.map: errorMsg =>
        div(
          role := "alert",
          cls  := "alert alert-error shadow-lg",
          span(errorMsg)
        )
  )
