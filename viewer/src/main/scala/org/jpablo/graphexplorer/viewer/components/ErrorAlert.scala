package org.jpablo.graphexplorer.viewer.components

import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.viewer.widgets.{AlertBox, AlertTone, ToastCorner}

/** Displays the latest error message in a floating alert box. */
def ErrorAlert(errors: EventBus[String]): HtmlElement =
  val latestError = Var[Option[String]](None)

  ToastCorner(
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
    child.maybe <-- latestError.signal.map:
      _.map: errorMsg =>
        AlertBox(AlertTone.Error, cls := "shadow-lg", span(errorMsg))
  )
