package org.jpablo.graphexplorer.graphviz.dotlang

/** Abstract syntax for the DOT language.
  *
  * Mirrors the official grammar at https://graphviz.org/doc/info/lang.html as
  * closely as is useful for the `dot` layout port. This is a faithful parse
  * tree, not a semantic graph model — attribute scoping, default-statement
  * resolution and node deduplication happen in a later milestone (M1).
  */

/** A DOT identifier value.
  *
  * @param value
  *   the logical (unescaped) text. For quoted strings, surrounding quotes are
  *   removed and `\"`/line-continuations are resolved. For HTML-like strings
  *   the raw inner markup is kept verbatim.
  * @param html
  *   true when the id came from an HTML-like `<...>` string.
  */
final case class Id(value: String, html: Boolean = false) derives CanEqual

enum Compass derives CanEqual:
  case N, NE, E, SE, S, SW, W, NW, C, Underscore // Underscore == "_"

object Compass:
  /** Parse a bare word into a compass point, if it is one. */
  def from(s: String): Option[Compass] = s match
    case "n"  => Some(N)
    case "ne" => Some(NE)
    case "e"  => Some(E)
    case "se" => Some(SE)
    case "s"  => Some(S)
    case "sw" => Some(SW)
    case "w"  => Some(W)
    case "nw" => Some(NW)
    case "c"  => Some(C)
    case "_"  => Some(Underscore)
    case _    => None

/** `':' ID [ ':' compass_pt ] | ':' compass_pt` */
final case class Port(name: Option[Id], compass: Option[Compass]) derives CanEqual

/** `ID [ port ]` */
final case class NodeId(id: Id, port: Option[Port] = None) derives CanEqual

/** Ordered `key = value` pairs, flattened across consecutive `[ ... ][ ... ]`. */
type AttrList = List[(Id, Id)]

enum AttrTarget derives CanEqual:
  case Graph, Node, Edge

/** A `subgraph` (or anonymous `{ ... }`); `cluster_*`-named subgraphs are
  * detected later, the parser keeps the id verbatim.
  */
final case class Subgraph(id: Option[Id], stmts: List[Stmt]) derives CanEqual

/** An edge operand: either a node or a (sub)graph. */
enum EdgeEnd derives CanEqual:
  case Node(node: NodeId)
  case Sub(subgraph: Subgraph)

enum Stmt derives CanEqual:
  case NodeStmt(node: NodeId, attrs: AttrList)
  /** `ends.length >= 2`; the edge operator (`->`/`--`) is implied by the graph. */
  case EdgeStmt(ends: List[EdgeEnd], attrs: AttrList)
  case AttrStmt(target: AttrTarget, attrs: AttrList)
  /** Top-level `ID '=' ID` (a graph attribute). */
  case Assign(name: Id, value: Id)
  case SubStmt(subgraph: Subgraph)

final case class Graph(
    strict:   Boolean,
    directed: Boolean, // true => `digraph`, edges use `->`
    id:       Option[Id],
    stmts:    List[Stmt]
) derives CanEqual
