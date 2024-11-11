package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveHtmlElement
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*
import com.raquo.laminar.api.features.unitArrows

def ChatView(state: ViewerState) =
  val chatText = Var("")
  val chatHistory = Var(List.empty[String])
  val showConfiguration = Var(false)
  Dialog(cls("modal-open") <-- state.chatOpen.signal)(
    div(
      cls := "flex justify-between items-center",
      h2(cls := "text-lg", text <-- showConfiguration.signal.map(if _ then "Chat Configuration" else "History")),
      Tooltip(
        text = "Chat configuration",
        cls := "flex-none",
        input(idAttr := "123", tpe := "checkbox", cls := "drawer-toggle"),
        label(
          forId := "123",
          cls   := "btn btn-ghost",
          cls("btn-active") <-- showConfiguration,
          onClick --> showConfiguration.toggle()
        ).tiny.gearIcon
      )
    ),
    children(
      table(
        cls := "table table-xs table-pin-rows my-2",
        tbody(
          children <-- chatHistory.signal.map(_.map(message => tr(td(message))))
        )
      ),
      textArea(
        idAttr      := "chat-view",
        cls         := "textarea textarea-bordered w-full h-32",
        placeholder := "Instructions to modify the graph",
        controlled(
          value <-- chatText,
          onInput.mapToValue --> chatText.set
        )
      )
    ) <-- !showConfiguration.signal
  )(
    div(
      cls := "flex gap-2",
      Button(
        "Send",
        onClick --> { _ =>
          val text = chatText.now()
          dom.console.log(s"Chat message: $text")
          chatHistory.update(_ :+ text)
          chatText.set("")
        }
      ).primary.small,
      Button("Close", onClick --> state.chatOpen.set(false)).small
    )
  )
