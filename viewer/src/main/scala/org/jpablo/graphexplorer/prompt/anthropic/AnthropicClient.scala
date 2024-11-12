package org.jpablo.graphexplorer.prompt.anthropic

import com.raquo.airstream.web.FetchStream
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.prompt.GraphvizPrompt
import org.jpablo.graphexplorer.prompt.GraphvizPrompt.{PromptContext, createPrompt}
import upickle.default.*

import scala.scalajs.js.JSON

case class AnthropicChatConfig(
    providerEndpoint: String,
    selectedModel:    String,
    maxTokens:        Int,
    apiKey:           String,
    systemPrompt:     String
) derives ReadWriter

def anthropicClient(config: AnthropicChatConfig, source: String, requestText: String)(using
    Owner
): EventStream[AnthropicResponse.ChatResponse] =
  val chatRequest =
    AnthropicChatRequest(
      model      = config.selectedModel,
      max_tokens = config.maxTokens,
      system     = GraphvizPrompt.systemPrompt,
      messages   = List(createPrompt(PromptContext(currentGraph = source, userCommand = requestText)))
    )
  val responseStream =
    FetchStream.post(
      url = "http://localhost:3000/api/chat",
      _.headers(
        "Content-Type"      -> "application/json",
        "x-api-key"         -> s"Bearer ${config.apiKey}",
        "anthropic-version" -> "2023-06-01"
      ),
      _.body(write(chatRequest))
    )
  responseStream.map: response =>
    dom.console.log(s"Chat response", JSON.parse(response))
    read[AnthropicResponse.ChatResponse](response.text)
