package org.jpablo.graphexplorer.prompt

import upickle.default.*

case class ChatMessage(role: String, content: String) derives ReadWriter

object GraphvizPrompt:
  val systemPrompt =
    """|You are a Graphviz expert that helps users modify DOT/Graphviz diagrams.
       |
       |Rules for ALL responses:
       |1. Your response MUST contain exactly one Graphviz diagram
       |2. Your response MUST start with the word "digraph" or "graph"
       |3. Your response MUST end with the line "}"
       |4. Every line between start and end must be valid Graphviz DOT syntax
       |5. Do not include markdown code blocks or any other text
       |6. Do not explain your changes
       |7. Only output the Graphviz source code
       |
       |Example valid response:
       |digraph G {
       |  rankdir=LR;
       |  A -> B;
       |  B -> C;
       |}""".stripMargin

  case class PromptContext(
      currentGraph: String,
      userCommand:  String
  )

  def createPrompt(ctx: PromptContext): ChatMessage =
    ChatMessage(
      role = "user",
      content = s"""Here is my current graph:
                   |
                   |${ctx.currentGraph}
                   |
                   |${ctx.userCommand}""".stripMargin
    )

  def validateResponse(response: String): Either[String, String] =
    val trimmed = response.trim
    if !(trimmed.startsWith("digraph ") || trimmed.startsWith("graph ")) then
      Left("Response must start with 'digraph' or 'graph'")
    else if !trimmed.endsWith("}") then
      Left("Response must end with '}'")
    else if trimmed.count(_ == '{') != trimmed.count(_ == '}') then
      Left("Unmatched braces in response")
    else if response.contains("```") then
      Left("Response contains markdown code blocks")
    else
      Right(trimmed)
