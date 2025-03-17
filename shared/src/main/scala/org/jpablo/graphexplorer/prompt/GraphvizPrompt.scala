package org.jpablo.graphexplorer.prompt

case class ChatMessage(
    role:    String,
    content: String
)

object GraphvizPrompt:
  private val systemPrompt = """You are a Graphviz expert that helps users modify DOT/Graphviz diagrams.
                               |
                               |Rules for ALL responses:
                               |1. Your response MUST contain exactly one Graphviz diagram
                               |2. Your response MUST start with the line "digraph G {"
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

  def createPrompt(ctx: PromptContext): List[ChatMessage] = List(
    ChatMessage(
      role    = "system",
      content = systemPrompt
    ),
    ChatMessage(
      role = "user",
      content = s"""Here is my current graph:
                   |
                   |${ctx.currentGraph}
                   |
                   |${ctx.userCommand}""".stripMargin
    )
  )

  def validateResponse(response: String): Either[String, String] =
    val trimmed = response.trim
    if !trimmed.startsWith("digraph G {") then
      Left("Response must start with 'digraph G {'")
    else if !trimmed.endsWith("}") then
      Left("Response must end with '}'")
    else if trimmed.count(_ == '{') != trimmed.count(_ == '}') then
      Left("Unmatched braces in response")
    else if response.contains("```") then
      Left("Response contains markdown code blocks")
    else
      Right(trimmed)
