package org.jpablo.graphexplorer.graphviz.model

import org.jpablo.graphexplorer.graphviz.dotlang as ast
import org.jpablo.graphexplorer.graphviz.html.ImageDim
import scala.collection.mutable

/** A semantic graph with attributes resolved against DOT's default-statement
  * scoping rules. The layout pipeline (M2+) consumes this, not the raw AST.
  *
  * M1 scope: flat node/edge collections with fully-merged attributes. Cluster
  * structure is intentionally NOT modelled yet (M6) — for layout-stage
  * verification a flattened view is what the early pipeline needs.
  */
/** @param htmlKeys keys whose value came from an HTML-like `<...>` string
  *                 (Graphviz's `LT_HTML` label flag) — the value in [[toMap]]
  *                 is the raw markup; layout/output treat it as HTML, not text. */
final case class Attrs(toMap: Map[String, String], htmlKeys: Set[String] = Set.empty) derives CanEqual:
  def get(k: String): Option[String]            = toMap.get(k)
  def getOrElse(k: String, d: String): String   = toMap.getOrElse(k, d)
  def isHtml(k: String): Boolean                = htmlKeys.contains(k)
  /** Later attrs win for both value AND html-ness — a key overridden by a
    * non-HTML value loses its HTML marking, and vice-versa. */
  def ++(o: Attrs): Attrs =
    Attrs(toMap ++ o.toMap, (htmlKeys -- o.toMap.keySet) ++ o.htmlKeys)

object Attrs:
  val empty: Attrs = Attrs(Map.empty)
  /** Later duplicate keys win — Graphviz's last-attribute-wins semantics. */
  def of(ps: Seq[(ast.Id, ast.Id)]): Attrs =
    val resolved = ps.foldLeft(Map.empty[String, (String, Boolean)]) {
      case (acc, (k, v)) => acc.updated(k.value, (v.value, v.html))
    }
    Attrs(resolved.view.mapValues(_._1).toMap, resolved.collect { case (k, (_, true)) => k }.toSet)

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

  /** `chkPort`'s `port.name` (utils.c): the raw port spec after its first
    * `:` if any, else the whole — i.e. the compass when `field:compass`
    * was given (`f2:s` ⇒ `s`), else the field/compass token. Drives the
    * svg edge `<title>` (`\E` expansion, labels.c), distinct from the
    * json0 `tailport`/`headport` which keep the full `field:compass`. */
  private def portName(p: ast.Port): String =
    p.compass
      .map(_.toString.toLowerCase.replace("underscore", "_"))
      .orElse(p.name.map(_.value))
      .getOrElse("")
  def tailPortName: Option[String] = tailPort.map(portName).filter(_.nonEmpty)
  def headPortName: Option[String] = headPort.map(portName).filter(_.nonEmpty)

/** A subgraph in the resolved tree (M6 clusters). Additive: non-clustered
  * graphs have an empty [[RGraph.subgraphs]] so every earlier milestone is
  * byte-identical.
  *
  * @param id       resolved cgraph name — the verbatim id for named subgraphs
  *                 (`cluster_0`), or the synthesized anonymous name `%<n>`.
  * @param label    the subgraph's graph-`label` attr, `""` if none.
  * @param isCluster whether `id` starts with `cluster` (drives layout + emit).
  * @param rank     `rank=same|min|max|source|sink`, if set (a rank-constraint
  *                 subgraph — evicts its nodes from any cluster membership).
  * @param nodeIds  nodes referenced *directly* in this subgraph (first-mention
  *                 order). Raw membership; the emitter applies the ownership
  *                 rule (rank-constraint eviction) and re-orders by global id.
  * @param edgeIdxs indices into [[RGraph.edges]] for edges declared *directly*
  *                 in this subgraph.
  * @param children nested subgraphs, in declaration order.
  */
final case class RSubgraph(
    id:        String,
    label:     String,
    isCluster: Boolean,
    rank:      Option[String],
    nodeIds:   Vector[String],
    edgeIdxs:  Vector[Int],
    children:  Vector[RSubgraph]
) derives CanEqual

final case class RGraph(
    strict:    Boolean,
    directed:  Boolean,
    name:      Option[String],
    rootAttrs: Attrs,
    nodes:     Vector[RNode],
    edges:     Vector[REdge],
    subgraphs: Vector[RSubgraph] = Vector.empty,
    // Every graph-scoped attribute key declared *anywhere* (root or a
    // subgraph), in first-declaration order — the `agnxtattr` order gv's
    // `write_attrs` walks for the root object. A key declared only inside a
    // cluster (e.g. `label` via `cluster_0`) still surfaces at the root with
    // its default value.
    graphAttrKeys: Vector[String] = Vector.empty,
    // External image-dimension table (src → natural size). Not in the DOT —
    // supplied by the caller, mirroring viz-js's `images` render option — so a
    // referenced `<IMG>`/`image=` can be sized and an `<image>` element emitted.
    images: ImageDim.Table = ImageDim.empty
) derives CanEqual

/** AST → [[RGraph]]. Implements DOT scoping: `node`/`edge`/`graph` default
  * statements affect items created *after* them in the current scope and are
  * inherited by nested subgraphs; a node is seeded with the defaults in force
  * at first mention, then each later mention merges explicit attributes.
  */
object AttrResolver:

  private final case class Scope(node: Attrs, edge: Attrs, graph: Attrs)

  /** What a single subgraph level directly declares — the raw material for one
    * [[RSubgraph]] (the level's own `label`/`rank`) or, at the root, the
    * top-level `subgraphs` (via `children`). */
  private final case class Collected(
      nodeIds:  Vector[String],
      edgeIdxs: Vector[Int],
      children: Vector[RSubgraph],
      label:    String,
      rank:     Option[String]
  )

  def resolve(g: ast.Graph): RGraph =
    val nodes     = mutable.LinkedHashMap.empty[String, Attrs]
    val edges     = mutable.ListBuffer.empty[REdge]
    var rootAttrs = Attrs.empty

    // Anonymous cgraph object ids (id.c `idmap`): a *single* counter shared by
    // the unnamed root graph, every edge (all DOT edges are keyless ⇒
    // anonymous), and every anonymous subgraph, ticked in parse/creation
    // order. The Nth anon object gets id `counter*2+1`; an anonymous subgraph
    // prints as `%<id>` (id.c `agnameof`). Named nodes/subgraphs use a string
    // id ⇒ they do **not** tick. Derived + oracle-verified (PORT.md §5.2).
    var anonCtr = if g.id.isEmpty then 1 else 0 // unnamed root consumes id 1

    // graph-attribute keys in first-declaration order, across all levels
    val graphAttrKeys = mutable.LinkedHashSet.empty[String]

    def ensureNode(id: String, sc: Scope): Unit =
      if !nodes.contains(id) then nodes(id) = sc.node

    def mergeNode(id: String, sc: Scope, explicit: Attrs): Unit =
      nodes(id) = (if nodes.contains(id) then nodes(id) else sc.node) ++ explicit

    /** Allocate a subgraph's resolved name (ticking the anon counter for
      * anonymous `{ … }`) then recurse — the `{`-time id must precede any
      * anonymous object created inside. */
    def enterSub(sg: ast.Subgraph, sc: Scope): RSubgraph =
      val id = sg.id match
        case Some(name) => name.value
        case None       => val s = s"%${anonCtr * 2 + 1}"; anonCtr += 1; s
      val col = walk(sg.stmts, sc, isRoot = false)
      RSubgraph(id, col.label, id.startsWith("cluster"), col.rank,
        col.nodeIds, col.edgeIdxs, col.children)

    def walk(stmts: List[ast.Stmt], sc0: Scope, isRoot: Boolean): Collected =
      var sc         = sc0
      val levelNodes = mutable.LinkedHashSet.empty[String] // dedup, first-mention
      val levelEdges = mutable.ListBuffer.empty[Int]
      val children   = mutable.ListBuffer.empty[RSubgraph]
      var label      = ""
      var rank       = Option.empty[String]

      def captureGraphAttr(k: String, v: String): Unit =
        if k == "label" then label = v
        if k == "rank" then rank = Some(v)

      stmts.foreach {
        case ast.Stmt.AttrStmt(target, as) =>
          val a = Attrs.of(as)
          sc = target match
            case ast.AttrTarget.Node  => sc.copy(node = sc.node ++ a)
            case ast.AttrTarget.Edge  => sc.copy(edge = sc.edge ++ a)
            case ast.AttrTarget.Graph =>
              if isRoot then rootAttrs = rootAttrs ++ a
              as.foreach { case (k, v) => graphAttrKeys += k.value; captureGraphAttr(k.value, v.value) }
              sc.copy(graph = sc.graph ++ a)

        case ast.Stmt.Assign(k, v) =>
          val a = Attrs(Map(k.value -> v.value), if v.html then Set(k.value) else Set.empty)
          if isRoot then rootAttrs = rootAttrs ++ a
          graphAttrKeys += k.value
          captureGraphAttr(k.value, v.value)
          sc = sc.copy(graph = sc.graph ++ a)

        case ast.Stmt.NodeStmt(nid, as) =>
          mergeNode(nid.id.value, sc, Attrs.of(as))
          levelNodes += nid.id.value

        case ast.Stmt.EdgeStmt(ends, as) =>
          val ea = Attrs.of(as)
          val ids = ends.map {
            case ast.EdgeEnd.Node(n) => List((n.id.value, n.port))
            case ast.EdgeEnd.Sub(sg) =>
              val sub = enterSub(sg, sc)
              children += sub
              sub.nodeIds.map((_, None)) // subgraph ends: no port
          }
          ids.foreach(_.foreach { case (nm, _) => levelNodes += nm })
          ids.zip(ids.drop(1)).foreach { case (tails, heads) =>
            for (t, tp) <- tails; (h, hp) <- heads do
              ensureNode(t, sc)
              ensureNode(h, sc)
              levelEdges += edges.length     // record index before append
              edges += REdge(t, h, sc.edge ++ ea, tp, hp)
              anonCtr += 1                    // every edge is anonymous ⇒ tick
          }

        case ast.Stmt.SubStmt(sg) =>
          children += enterSub(sg, sc)
      }

      Collected(levelNodes.toVector, levelEdges.toVector, children.toVector, label, rank)

    val root = walk(g.stmts, Scope(Attrs.empty, Attrs.empty, Attrs.empty), isRoot = true)

    RGraph(
      strict    = g.strict,
      directed  = g.directed,
      name      = g.id.map(_.value),
      rootAttrs = rootAttrs,
      nodes     = nodes.iterator.map { case (id, a) => RNode(id, a) }.toVector,
      edges     = edges.toVector,
      subgraphs = root.children,
      graphAttrKeys = graphAttrKeys.toVector
    )

end AttrResolver
