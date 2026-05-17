package org.jpablo.graphexplorer.graphviz.model

import org.jpablo.graphexplorer.graphviz.dotlang as ast
import scala.collection.mutable

/** A semantic graph with attributes resolved against DOT's default-statement
  * scoping rules. The layout pipeline (M2+) consumes this, not the raw AST.
  *
  * M1 scope: flat node/edge collections with fully-merged attributes. Cluster
  * structure is intentionally NOT modelled yet (M6) — for layout-stage
  * verification a flattened view is what the early pipeline needs.
  */
final case class Attrs(toMap: Map[String, String]) derives CanEqual:
  def get(k: String): Option[String]            = toMap.get(k)
  def getOrElse(k: String, d: String): String   = toMap.getOrElse(k, d)
  def ++(o: Attrs): Attrs                        = Attrs(toMap ++ o.toMap)

object Attrs:
  val empty: Attrs = Attrs(Map.empty)
  /** Later duplicate keys win — Graphviz's last-attribute-wins semantics. */
  def of(ps: Seq[(ast.Id, ast.Id)]): Attrs =
    Attrs(ps.map { case (k, v) => k.value -> v.value }.toMap)

final case class RNode(id: String, attrs: Attrs) derives CanEqual
final case class REdge(
    tail:     String,
    head:     String,
    attrs:    Attrs,
    tailPort: Option[ast.Port] = None,
    headPort: Option[ast.Port] = None
) derives CanEqual:
  /** `field` / `field:compass` / `compass` string (json0/dot_json form). */
  private def portStr(p: ast.Port): String =
    val c = p.compass.map(_.toString.toLowerCase.replace("underscore", "_"))
    (p.name.map(_.value), c) match
      case (Some(n), Some(cp)) => s"$n:$cp"
      case (Some(n), None)     => n
      case (None, Some(cp))    => cp
      case (None, None)        => ""
  def tailPortStr: Option[String] = tailPort.map(portStr).filter(_.nonEmpty)
  def headPortStr: Option[String] = headPort.map(portStr).filter(_.nonEmpty)

final case class RGraph(
    strict:    Boolean,
    directed:  Boolean,
    name:      Option[String],
    rootAttrs: Attrs,
    nodes:     Vector[RNode],
    edges:     Vector[REdge]
) derives CanEqual

/** AST → [[RGraph]]. Implements DOT scoping: `node`/`edge`/`graph` default
  * statements affect items created *after* them in the current scope and are
  * inherited by nested subgraphs; a node is seeded with the defaults in force
  * at first mention, then each later mention merges explicit attributes.
  */
object AttrResolver:

  private final case class Scope(node: Attrs, edge: Attrs, graph: Attrs)

  def resolve(g: ast.Graph): RGraph =
    val nodes     = mutable.LinkedHashMap.empty[String, Attrs]
    val edges     = mutable.ListBuffer.empty[REdge]
    var rootAttrs = Attrs.empty

    def ensureNode(id: String, sc: Scope): Unit =
      if !nodes.contains(id) then nodes(id) = sc.node

    def mergeNode(id: String, sc: Scope, explicit: Attrs): Unit =
      nodes(id) = (if nodes.contains(id) then nodes(id) else sc.node) ++ explicit

    def collectNodeIds(stmts: List[ast.Stmt]): List[String] =
      stmts.flatMap {
        case ast.Stmt.NodeStmt(n, _)  => List(n.id.value)
        case ast.Stmt.EdgeStmt(es, _) => es.flatMap(endNodeIds)
        case ast.Stmt.SubStmt(sg)     => collectNodeIds(sg.stmts)
        case _                        => Nil
      }

    def endNodeIds(e: ast.EdgeEnd): List[String] = e match
      case ast.EdgeEnd.Node(n) => List(n.id.value)
      case ast.EdgeEnd.Sub(sg) => collectNodeIds(sg.stmts)

    def walk(stmts: List[ast.Stmt], sc0: Scope, isRoot: Boolean): Unit =
      var sc = sc0
      stmts.foreach {
        case ast.Stmt.AttrStmt(target, as) =>
          val a = Attrs.of(as)
          sc = target match
            case ast.AttrTarget.Node  => sc.copy(node = sc.node ++ a)
            case ast.AttrTarget.Edge  => sc.copy(edge = sc.edge ++ a)
            case ast.AttrTarget.Graph =>
              if isRoot then rootAttrs = rootAttrs ++ a
              sc.copy(graph = sc.graph ++ a)

        case ast.Stmt.Assign(k, v) =>
          val a = Attrs(Map(k.value -> v.value))
          if isRoot then rootAttrs = rootAttrs ++ a
          sc = sc.copy(graph = sc.graph ++ a)

        case ast.Stmt.NodeStmt(nid, as) =>
          mergeNode(nid.id.value, sc, Attrs.of(as))

        case ast.Stmt.EdgeStmt(ends, as) =>
          val ea  = Attrs.of(as)
          val ids = ends.map {
            case ast.EdgeEnd.Node(n) => List((n.id.value, n.port))
            case ast.EdgeEnd.Sub(sg) =>
              walk(sg.stmts, sc, isRoot = false)
              collectNodeIds(sg.stmts).map((_, None)) // subgraph ends: no port
          }
          ids.zip(ids.drop(1)).foreach { case (tails, heads) =>
            for (t, tp) <- tails; (h, hp) <- heads do
              ensureNode(t, sc)
              ensureNode(h, sc)
              edges += REdge(t, h, sc.edge ++ ea, tp, hp)
          }

        case ast.Stmt.SubStmt(sg) =>
          walk(sg.stmts, sc, isRoot = false)
      }

    walk(g.stmts, Scope(Attrs.empty, Attrs.empty, Attrs.empty), isRoot = true)

    RGraph(
      strict    = g.strict,
      directed  = g.directed,
      name      = g.id.map(_.value),
      rootAttrs = rootAttrs,
      nodes     = nodes.iterator.map { case (id, a) => RNode(id, a) }.toVector,
      edges     = edges.toVector
    )

end AttrResolver
