package org.jpablo.graphexplorer.prompt.anthropic

import org.jpablo.graphexplorer.prompt.ChatMessage
import upickle.default.*

case class AnthropicChatRequest(
    model:      String,
    max_tokens: Int,
    system:     String,
    messages:   List[ChatMessage]
) derives ReadWriter

object AnthropicResponse:
  case class ChatResponse(
      id:            String,
      `type`:        String,
      role:          String,
      model:         String,
      content:       List[ChatContent],
      stop_reason:   String,
      stop_sequence: Option[String],
      usage:         ChatUsage
  ) derives ReadWriter

  case class ChatContent(`type`: String, text: String) derives ReadWriter

  case class ChatUsage(input_tokens: Int, output_tokens: Int) derives ReadWriter
