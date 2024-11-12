package org.jpablo.graphexplorer.viewer.components.leftPanel

import com.raquo.laminar.api.L.*
import com.raquo.laminar.api.features.unitArrows
import com.raquo.laminar.nodes.ReactiveHtmlElement
import io.laminext.syntax.core.*
import org.jpablo.graphexplorer.prompt.openai.OpenAIChatConfig
import org.jpablo.graphexplorer.viewer.state.ViewerState
import org.jpablo.graphexplorer.viewer.widgets.*
import org.jpablo.graphexplorer.viewer.widgets.Icons.*

def ChatView(state: ViewerState) =
  val chatText = Var("")
  val chatHistory = Var(List.empty[String])

  Dialog(
    header = cls("modal-open") <-- state.chatOpen.signal,
  )(
    // --- Dialog header ---
    div(
      cls := "flex justify-between items-center",
      h2(
        cls := "text-lg",
        text <-- state.chatShowConfiguration.signal.map(if _ then "Chat Configuration" else "History")
      ),
      div(
        cls := "flex-none",
        input(idAttr := "chat-show-configuration", tpe := "checkbox", cls := "drawer-toggle"),
        label(
          forId := "chat-show-configuration",
          cls   := "btn btn-ghost",
          cls("btn-active") <-- state.chatShowConfiguration,
          onClick --> state.chatShowConfiguration.toggle()
        ).tiny.gearIcon
      )
    ),
    // Add a wrapper div with flex-grow and overflow handling
    div(
      // --- Chat configuration ---
      children(
        FormInput(
          labelText       = "Provider Endpoint",
          placeholderText = OpenAIChatConfig.default.providerEndpoint,
          inputValue      = state.oaiChatConfig.zoomLazy(_.providerEndpoint)((c, v) => c.copy(providerEndpoint = v))
        ),
        FormInput(
          labelText       = "Model",
          placeholderText = OpenAIChatConfig.default.selectedModel,
          inputValue      = state.oaiChatConfig.zoomLazy(_.selectedModel)((c, v) => c.copy(selectedModel = v))
        ),
        FormInput(
          labelText       = "API Key",
          placeholderText = "sk-...",
          inputValue      = state.oaiChatConfig.zoomLazy(_.apiKey)((c, v) => c.copy(apiKey = v)),
          inputType       = "password"
        )
      ) <-- state.chatShowConfiguration.signal,
      // --- Chat history and input ---
      children(
        table(
          cls := "table table-xs table-pin-rows my-2",
          tbody(children <-- chatHistory.signal.map(_.map(message => tr(td(message)))))
        ),
        div(cls := "text-lg", "Type your instructions:"),
        textArea(
          idAttr      := "chat-view",
          cls         := "textarea textarea-bordered w-full h-32",
          placeholder := "Instructions to modify the graph",
          controlled(value <-- chatText, onInput.mapToValue --> chatText.set)
        )
      ) <-- !state.chatShowConfiguration.signal
    )
  )(
    // --- Dialog footer ---
    div(
      cls := "flex gap-2",
      Button(
        "Send",
        onClick --> { _ =>
          val requestText = chatText.now()
          state.submitChatRequest(requestText)
          chatHistory.update(_ :+ requestText)
        }
      ).primary.small,
      Button("Close", onClick --> state.chatOpen.set(false)).small
    )
  )
