package org.jpablo.graphexplorer.graphviz.dotlang

import fastparse.*
import scala.annotation.{switch, tailrec}

/** A faithful parser for the DOT language (https://graphviz.org/doc/info/lang.html).
  *
  * Pure Scala (no platform APIs) so it cross-compiles to JS and the JVM. This
  * is M0's front-end: it produces the [[Ast]] only — no layout, no attribute
  * resolution. Whitespace handling covers `//`, `#` and `/* */` comments.
  */
object DotParser:

  // ── Whitespace: spaces + `//` / `#` line comments + `/* */` block comments ──
  // State machine adapted from fastparse's JavaWhitespace.
  given dotWhitespace: Whitespace = new Whitespace:
    def apply(ctx: ParsingRun[?]): ParsingRun[Unit] =
      val input = ctx.input
      @tailrec def rec(current: Int, state: Int): ParsingRun[Unit] =
        if !input.isReachable(current) then
          if state == 0 || state == 1 then ctx.freshSuccessUnit(current)
          else if state == 2 then ctx.freshSuccessUnit(current - 1)
          else ctx.freshFailure(current) // unclosed block comment
        else
          val c = input(current)
          (state: @switch) match
            case 0 => // normal
              c match
                case ' ' | '\t' | '\n' | '\r' => rec(current + 1, 0)
                case '#'                       => rec(current + 1, 1)
                case '/'                       => rec(current + 1, 2)
                case _                         => ctx.freshSuccessUnit(current)
            case 1 => // line comment (// or #) until newline
              rec(current + 1, if c == '\n' then 0 else 1)
            case 2 => // saw '/'
              c match
                case '/' => rec(current + 1, 1)
                case '*' => rec(current + 1, 3)
                case _   => ctx.freshSuccessUnit(current - 1)
            case 3 => // inside block comment
              rec(current + 1, if c == '*' then 4 else 3)
            case 4 => // block comment, saw '*'
              c match
                case '/' => rec(current + 1, 0)
                case '*' => rec(current + 1, 4)
                case _   => rec(current + 1, 3)
      rec(ctx.index, 0)

  // ── Identifier tokens ──────────────────────────────────────────────────────
  // DOT allows extended (\200-\377) bytes in bare identifiers.
  private def isIdStart(c: Char): Boolean = c == '_' || c.isLetter || c >= '\u0080'
  private def isIdChar(c: Char): Boolean  = c == '_' || c.isLetterOrDigit || c >= '\u0080'

  /** Case-insensitive keyword with an identifier-boundary guard. */
  private def kw[$: P](s: String): P[Unit] =
    P(IgnoreCase(s) ~~ !CharPred(isIdChar))

  private def idName[$: P]: P[Id] =
    P((CharPred(isIdStart) ~~ CharsWhile(isIdChar, 0)).!).map(s => Id(s))

  private def idNumeral[$: P]: P[Id] =
    P(
      ("-".? ~~ (("." ~~ CharsWhileIn("0-9")) |
        (CharsWhileIn("0-9") ~~ ("." ~~ CharsWhileIn("0-9", 0)).?))).!
    ).map(s => Id(s))

  private def quotedChar[$: P]: P[String] =
    P(
      "\\\"".map(_ => "\"")                     // \" -> "
        | "\\\r\n".map(_ => "")                  // line continuation (CRLF)
        | "\\\n".map(_ => "")                    // line continuation (LF)
        | ("\\" ~~ AnyChar.!).map(s => "\\" + s) // keep other escapes verbatim
        | CharsWhile(c => c != '"' && c != '\\').!
    )

  private def quotedRaw[$: P]: P[String] =
    P("\"" ~~ quotedChar.repX ~~ "\"").map(_.mkString)

  /** Double-quoted string, including `"a" + "b"` concatenation. */
  private def idQuoted[$: P]: P[Id] =
    P(quotedRaw ~ ("+" ~ quotedRaw).rep).map { case (h, t) => Id((h +: t).mkString) }

  /** HTML-like string `<...>` with balanced angle brackets; kept verbatim. */
  private def htmlBody[$: P]: P[Unit] =
    P((CharsWhile(c => c != '<' && c != '>') | ("<" ~~ htmlBody ~~ ">")).repX)

  private def idHtml[$: P]: P[Id] =
    P("<" ~~ htmlBody.! ~~ ">").map(s => Id(s, html = true))

  private def id[$: P]: P[Id] = P(idHtml | idQuoted | idNumeral | idName)

  // Optional statement / attribute separators, normalised to Unit so they
  // never leak into the result tuples.
  private def semiOpt[$: P]: P[Unit]    = P(";".rep(max = 1))
  private def attrSepOpt[$: P]: P[Unit] = P(CharIn(";,").rep(max = 1))

  // ── Ports / nodes ──────────────────────────────────────────────────────────
  private def port[$: P]: P[Port] =
    P(":" ~ id ~ (":" ~ idName).?).map {
      case (first, Some(second)) => Port(Some(first), Compass.from(second.value))
      case (first, None) =>
        Compass.from(first.value) match
          case Some(cp) => Port(None, Some(cp))
          case None     => Port(Some(first), None)
    }

  private def nodeId[$: P]: P[NodeId] =
    P(id ~ port.?).map { case (i, p) => NodeId(i, p) }

  // ── Attributes ─────────────────────────────────────────────────────────────
  private def attrPair[$: P]: P[(Id, Id)] =
    P(id ~ "=" ~ id ~ attrSepOpt).map { case (k, v) => (k, v) }

  private def attrGroups[$: P]: P[AttrList] =
    P(("[" ~ attrPair.rep ~ "]").rep).map(_.flatten.toList)

  private def attrGroups1[$: P]: P[AttrList] =
    P(("[" ~ attrPair.rep ~ "]").rep(1)).map(_.flatten.toList)

  private def attrTarget[$: P]: P[AttrTarget] =
    P(
      kw("graph").map(_ => AttrTarget.Graph)
        | kw("node").map(_ => AttrTarget.Node)
        | kw("edge").map(_ => AttrTarget.Edge)
    )

  // ── Subgraphs / statements ─────────────────────────────────────────────────
  private def subgraph[$: P]: P[Subgraph] =
    P((kw("subgraph") ~ id.?).? ~ "{" ~ stmtList ~ "}").map { case (pfx, stmts) =>
      Subgraph(pfx.flatten, stmts)
    }

  // cgraph grammar.y: `simple : nodelist | subgraph` with
  // `nodelist : node | nodelist ',' node` — a comma-separated node list is
  // valid BOTH as a standalone statement (`B, C, D;` declares three nodes)
  // and as an edge endpoint (`A -> {B, C}` / `a, b -> c` cross-products).
  // A multi-node list desugars to an anonymous subgraph of node statements,
  // which the resolver already handles identically.
  private def nodeList[$: P]: P[List[NodeId]] =
    P(nodeId ~ ("," ~ nodeId).rep).map((h, t) => h :: t.toList)

  private enum RawEnd derives CanEqual:
    case Nodes(ns: List[NodeId])
    case Sub(sg: Subgraph)

  private def rawEnd[$: P]: P[RawEnd] =
    P(subgraph.map(RawEnd.Sub(_)) | nodeList.map(RawEnd.Nodes(_)))

  private def toEdgeEnd(e: RawEnd): EdgeEnd = e match
    case RawEnd.Nodes(List(n)) => EdgeEnd.Node(n)
    case RawEnd.Nodes(ns)      => EdgeEnd.Sub(Subgraph(None, ns.map(Stmt.NodeStmt(_, Nil))))
    case RawEnd.Sub(sg)        => EdgeEnd.Sub(sg)

  private def edgeOp[$: P]: P[Unit] = P("->" | "--")

  private def attrStmt[$: P]: P[Stmt] =
    P(attrTarget ~ attrGroups1).map { case (t, as) => Stmt.AttrStmt(t, as) }

  private def assignStmt[$: P]: P[Stmt] =
    P(id ~ "=" ~ id).map { case (n, v) => Stmt.Assign(n, v) }

  private def edgeOrNodeStmt[$: P]: P[List[Stmt]] =
    P(rawEnd ~ (edgeOp ~ rawEnd).rep ~ attrGroups).map {
      case (first, rest, attrs) if rest.nonEmpty =>
        List(Stmt.EdgeStmt(toEdgeEnd(first) :: rest.iterator.map(toEdgeEnd).toList, attrs))
      // A standalone nodelist declares each node INLINE (appendnode) — no
      // subgraph is created (wrapping one would add a phantom `%N` object).
      case (RawEnd.Nodes(ns), _, attrs) => ns.map(Stmt.NodeStmt(_, attrs))
      case (RawEnd.Sub(sg), _, _)       => List(Stmt.SubStmt(sg))
    }

  private def stmt[$: P]: P[List[Stmt]] =
    P(attrStmt.map(List(_)) | assignStmt.map(List(_)) | edgeOrNodeStmt)

  private def stmtList[$: P]: P[List[Stmt]] =
    P((stmt ~ semiOpt).rep).map(_.flatten.toList)

  private def graphP[$: P]: P[Graph] =
    P(
      Start ~ (kw("strict").map(_ => true) | Pass.map(_ => false))
        ~ (kw("digraph").map(_ => true) | kw("graph").map(_ => false))
        ~ id.? ~ "{" ~ stmtList ~ "}" ~ End
    ).map { case (strict, directed, gid, stmts) => Graph(strict, directed, gid, stmts) }

  /** Parse a complete DOT document. */
  def parse(input: String): Either[String, Graph] =
    fastparse.parse(input, graphP(using _)) match
      case Parsed.Success(g, _) => Right(g)
      case f: Parsed.Failure    => Left(f.trace().longMsg)

end DotParser
