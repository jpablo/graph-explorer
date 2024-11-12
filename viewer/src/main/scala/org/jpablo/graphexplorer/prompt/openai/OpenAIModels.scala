package org.jpablo.graphexplorer.prompt.openai

import org.jpablo.graphexplorer.prompt.ChatMessage
import upickle.default.*

case class OpenAIChatConfig(
    providerEndpoint: String,
    selectedModel:    String,
    apiKey:           String,
    systemPrompt:     String
) derives ReadWriter

object OpenAIChatConfig:
  val default = OpenAIChatConfig(
    providerEndpoint = "https://api.openai.com/v1/chat/completions",
    selectedModel    = "gpt-4o-mini",
    apiKey           = "",
    systemPrompt     = "You are a helpful assistant..."
  )
end OpenAIChatConfig

case class OpenAIChatRequest(model: String, messages: List[ChatMessage]) derives ReadWriter

case class OpenAIChatResponse(
    id:                 String,
    `object`:           String,
    created:            Long,
    model:              String,
    choices:            List[OpenAIChatResponse.Choice],
    usage:              OpenAIChatResponse.Usage,
    system_fingerprint: String
) derives ReadWriter

object OpenAIChatResponse:

  case class Choice(
      index:         Int,
      message:       Message,
      logprobs:      Option[String],
      finish_reason: String
  ) derives ReadWriter

  case class Message(role: String, content: String, refusal: Option[String]) derives ReadWriter

  case class Usage(
      prompt_tokens:             Int,
      completion_tokens:         Int,
      total_tokens:              Int,
      prompt_tokens_details:     TokenDetails,
      completion_tokens_details: CompletionTokenDetails
  ) derives ReadWriter

  case class TokenDetails(cached_tokens: Int, audio_tokens: Int) derives ReadWriter

  case class CompletionTokenDetails(
      reasoning_tokens:           Int,
      audio_tokens:               Int,
      accepted_prediction_tokens: Int,
      rejected_prediction_tokens: Int
  ) derives ReadWriter

end OpenAIChatResponse
