package org.jpablo.graphexplorer.prompt.openai

import com.raquo.airstream.web.FetchStream
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.prompt.{ChatMessage, GraphvizPrompt}
import org.jpablo.graphexplorer.prompt.GraphvizPrompt.{PromptContext, createPrompt}
import upickle.default.*

import scala.scalajs.js.JSON

def openAIClient(config: OpenAIChatConfig, source: String, requestText: String)(using
    Owner
): EventStream[OpenAIChatResponse] =
  val chatRequest =
    OpenAIChatRequest(
      model = config.selectedModel,
      messages =
        List(
          ChatMessage(role = "system", content = GraphvizPrompt.systemPrompt),
          createPrompt(PromptContext(currentGraph = source, userCommand = requestText))
        )
    )
  val responseStream =
    FetchStream.post(
      url = "http://localhost:3000/api/chat",
      _.headers(
        "Content-Type"        -> "application/json",
        "Authorization"       -> s"Bearer ${config.apiKey}",
        "x-provider-endpoint" -> config.providerEndpoint
      ),
      _.body(write(chatRequest))
    )
  responseStream.map: response =>
    dom.console.log(s"Chat response", JSON.parse(response))
    read[OpenAIChatResponse](response.text)
