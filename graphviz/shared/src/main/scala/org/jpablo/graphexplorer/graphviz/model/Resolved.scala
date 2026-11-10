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
  *                 order): by a node statement, or as an end of an edge
  *                 statement. A subgraph end of an edge statement gives each
  *                 of its nodes, also the nodes of its inner subgraphs.
  *                 Raw membership; the emitter applies the ownership
  *                 rule (rank-constraint eviction) and re-orders by global id.
  * @param edgeIdxs indices into [[RGraph.edges]] for edges declared *directly*
  *                 in this subgraph.
  * @param children nested subgraphs, in declaration order.
  *
  * One [[RSubgraph]] holds all bodies of one cgraph subgraph. Sibling bodies
  * with the same name are one subgraph (cgraph `agsubg`), and the subgraph
  * keeps the position of its first body. Thus `nodeIds`, `edgeIdxs`,
  * `children` and `attrs` contain the data of all of its bodies.
  */
final case class RSubgraph(
    id:        String,
    label:     String,
    isCluster: Boolean,
    rank:      Option[String],
    nodeIds:   Vector[String],
    edgeIdxs:  Vector[Int],
    children:  Vector[RSubgraph],
    /** The subgraph's own graph-attribute values: the enclosing scope's graph
      * attrs at creation (DOT inheritance) overlaid with its locally-declared
      * ones (`graph [...]` / bare `k=v`). Drives the writers' cluster attr
      * echo (`sgAttrs`) and the cluster style/color rendering in `Svg`. */
    attrs:     Map[String, String] = Map.empty,
    /** cgraph `AGSEQ` of the subgraph: its position (from 1) in the creation
      * order of ALL subgraphs, plain and cluster. One counter serves the
      * whole graph (cgraph `agnextseq` on the shared closure). The svg
      * cluster id is `clust<seq>` (emit.c `getObjId`): in 202, `cluster_q`
      * is the sixth subgraph, so its id is `clust6`. A second body of a
      * named subgraph keeps the first seq, but its new children get new
      * seqs, so this order can differ from the preorder of the tree. */
    seq:       Int = 0
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

  /** One subgraph (or the root) while the resolver reads the AST. It becomes
    * one [[RSubgraph]] at the end.
    *
    * cgraph `agsubg(parent, name, TRUE)` finds a subgraph by its name among the
    * subgraphs of `parent`. If it finds one, it returns it, and the parser
    * adds the second body to that subgraph. Thus each builder can get more
    * than one body, and it keeps its data from one body to the next.
    *
    * @param graph     the graph-attribute values of the subgraph. A new builder
    *                  gets the values of the parent scope (cgraph
    *                  `agraphattr_init`). Each graph-attribute statement then
    *                  changes them. A new child subgraph gets these values.
    * @param localNode the node defaults that the bodies of this subgraph
    *                  declare. cgraph keeps them in the node dictionary of
    *                  the subgraph, so they apply in a later body too.
    * @param localEdge the edge defaults that the bodies declare, as for nodes.
    */
  private final class Builder(val id: String, var graph: Attrs, val seq: Int = 0):
    val nodeIds   = mutable.LinkedHashSet.empty[String] // first-mention order
    val edgeIdxs  = mutable.ListBuffer.empty[Int]
    val children  = mutable.ListBuffer.empty[Builder]   // first-body order
    val named     = mutable.HashMap.empty[String, Builder] // named children, by name
    var label     = ""
    var rank      = Option.empty[String]
    var localNode = Attrs.empty
    var localEdge = Attrs.empty

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

    // cgraph AGSEQ of the subgraphs (RSubgraph.seq): one counter for all
    // subgraphs, in creation order. The first subgraph gets 1.
    var sgSeq = 0

    // graph-attribute keys in first-declaration order, across all levels
    val graphAttrKeys = mutable.LinkedHashSet.empty[String]

    def ensureNode(id: String, sc: Scope): Unit =
      if !nodes.contains(id) then nodes(id) = sc.node

    def mergeNode(id: String, sc: Scope, explicit: Attrs): Unit =
      nodes(id) = (if nodes.contains(id) then nodes(id) else sc.node) ++ explicit

    /** Open a subgraph body, as cgraph `agsubg(parent, name, TRUE)` does.
      *
      * A named subgraph that `parent` already has opens again: the body adds
      * to it, and the subgraph keeps its first position. A subgraph with the
      * same name under a different parent is a different subgraph, because
      * `agsubg` looks only at the subgraphs of `parent`. Each anonymous
      * `{ … }` is a new subgraph, because cgraph gives it a new id. The
      * anonymous counter ticks before the body, because the `{`-time id comes
      * before the ids of the anonymous objects in the body. */
    def enterSub(sg: ast.Subgraph, sc: Scope, parent: Builder): Builder =
      def create(id: String): Builder =
        sgSeq += 1
        val b = Builder(id, sc.graph, sgSeq)
        parent.children += b
        b
      val b = sg.id match
        case Some(name) => parent.named.getOrElseUpdate(name.value, create(name.value))
        case None       => val s = s"%${anonCtr * 2 + 1}"; anonCtr += 1; create(s)
      // The node and edge dictionaries of a subgraph view the dictionaries of
      // its parent (cgraph `dtview`). Thus a body sees the current defaults of
      // the parent, and the defaults of this subgraph replace them. The graph
      // values of the subgraph go to its children: cgraph
      // `unviewsubgraphsattr` keeps the old value in an existing subgraph
      // when the parent declares a new value.
      val scope = Scope(sc.node ++ b.localNode, sc.edge ++ b.localEdge, b.graph)
      walk(sg.stmts, scope, b, isRoot = false)
      b

    /** All nodes of a subgraph: its own nodes, and the nodes of each inner
      * subgraph, at each level. cgraph puts a node in its subgraph and in each
      * outer subgraph. The result can have a node more than one time. */
    def allNodeIds(b: Builder): Iterator[String] =
      b.nodeIds.iterator ++ b.children.iterator.flatMap(allNodeIds)

    /** Make the [[RSubgraph]] tree of a builder, after all of its bodies. */
    def build(b: Builder): RSubgraph =
      val sgAttrs = b.graph.toMap
      // is_a_cluster (utils.c:692): case-INSENSITIVE "cluster" name prefix,
      // OR mapbool of the `cluster` attr (inherited defaults included).
      def mapBool(v: String): Boolean = v.toLowerCase match
        case "true" | "yes" => true
        case "false" | "no" => false
        case other          => other.toIntOption.exists(_ > 0)
      val isClu = b.id.toLowerCase.startsWith("cluster") || sgAttrs.get("cluster").exists(mapBool)
      RSubgraph(b.id, b.label, isClu, b.rank,
        b.nodeIds.toVector, b.edgeIdxs.toVector, b.children.iterator.map(build).toVector, sgAttrs,
        b.seq)

    /** Read one body into `level`. A statement changes the data of `level`,
      * and a default statement also changes the scope of the next statements
      * in the body. */
    def walk(stmts: List[ast.Stmt], sc0: Scope, level: Builder, isRoot: Boolean): Unit =
      var sc = sc0

      def captureGraphAttr(k: String, v: String): Unit =
        if k == "label" then level.label = v
        if k == "rank" then level.rank = Some(v)

      def setGraph(a: Attrs): Unit =
        if isRoot then rootAttrs = rootAttrs ++ a
        sc = sc.copy(graph = sc.graph ++ a)
        level.graph = sc.graph

      stmts.foreach {
        case ast.Stmt.AttrStmt(target, as) =>
          val a = Attrs.of(as)
          target match
            case ast.AttrTarget.Node =>
              sc = sc.copy(node = sc.node ++ a)
              level.localNode = level.localNode ++ a
            case ast.AttrTarget.Edge =>
              sc = sc.copy(edge = sc.edge ++ a)
              level.localEdge = level.localEdge ++ a
            case ast.AttrTarget.Graph =>
              as.foreach { case (k, v) => graphAttrKeys += k.value; captureGraphAttr(k.value, v.value) }
              setGraph(a)

        case ast.Stmt.Assign(k, v) =>
          graphAttrKeys += k.value
          captureGraphAttr(k.value, v.value)
          setGraph(Attrs(Map(k.value -> v.value), if v.html then Set(k.value) else Set.empty))

        case ast.Stmt.NodeStmt(nid, as) =>
          mergeNode(nid.id.value, sc, Attrs.of(as))
          level.nodeIds += nid.id.value

        case ast.Stmt.EdgeStmt(ends, as) =>
          val ea = Attrs.of(as)
          val ids = ends.map {
            case ast.EdgeEnd.Node(n) =>
              // cgraph appendnode: an edge endpoint declares its node THE
              // MOMENT it is parsed — before any later subgraph end declares
              // its members (`A -> {B,C}` seq-numbers A before B/C).
              ensureNode(n.id.value, sc)
              List((n.id.value, n.port))
            case ast.EdgeEnd.Sub(sg) =>
              val sub = enterSub(sg, sc, level)
              // cgraph expands a subgraph endpoint via agfstnode/agnxtnode
              // over the subgraph's node SET — ordered by global node seq
              // (creation order), NOT brace order: `2 -> {3;T1}` creates the
              // T1 edge first when T1 predates 3. (`nodes` insertion order
              // IS seq order.) Subgraph ends carry no port. The set of a
              // subgraph that opens again also has the nodes of its earlier
              // bodies. cgraph puts a node in each outer subgraph too, so the
              // set also has the nodes of each inner subgraph:
              // `x -> subgraph s { subgraph t { a } b }` gives `x -> a` and
              // `x -> b`.
              val seqIdx = nodes.keysIterator.zipWithIndex.toMap
              allNodeIds(sub).distinct.toVector.sortBy(seqIdx).map((_, None))
          }
          // cgraph `agedge` puts the two ends of each edge in the subgraph of
          // the edge statement. Thus `level` gets each end, also a node of an
          // inner subgraph of a subgraph end. This is the same as an explicit
          // edge to that node.
          ids.foreach(_.foreach { case (nm, _) => level.nodeIds += nm })
          ids.zip(ids.drop(1)).foreach { case (tails, heads) =>
            for (t, tp) <- tails; (h, hp) <- heads do
              ensureNode(t, sc)
              ensureNode(h, sc)
              level.edgeIdxs += edges.length // record index before append
              // `tailport`/`headport` ATTRS are the same channel as `:port`
              // syntax (the DOT parser stores syntax ports into these attrs;
              // common_init_edge reads them back) — syntax wins when both.
              // A single token stays a NAME; poly/record resolution decides
              // whether it is a compass (chkPort splits at the first ':').
              val eAttrs = sc.edge ++ ea
              def attrPort(key: String): Option[ast.Port] =
                eAttrs.get(key).filter(_.nonEmpty).map { v =>
                  v.split(":", 2) match
                    case Array(nm, cp) => ast.Port(Some(ast.Id(nm)).filter(_.value.nonEmpty), ast.Compass.from(cp))
                    case arr           => ast.Port(Some(ast.Id(arr.headOption.getOrElse(""))), None)
                }
              edges += REdge(t, h, eAttrs, tp.orElse(attrPort("tailport")), hp.orElse(attrPort("headport")))
              anonCtr += 1                    // every edge is anonymous ⇒ tick
          }

        case ast.Stmt.SubStmt(sg) =>
          enterSub(sg, sc, level)
      }

    val root = Builder(g.id.fold("")(_.value), Attrs.empty)
    walk(g.stmts, Scope(Attrs.empty, Attrs.empty, Attrs.empty), root, isRoot = true)

    RGraph(
      strict    = g.strict,
      directed  = g.directed,
      name      = g.id.map(_.value),
      rootAttrs = rootAttrs,
      nodes     = nodes.iterator.map { case (id, a) => RNode(id, a) }.toVector,
      edges     = edges.toVector,
      subgraphs = root.children.iterator.map(build).toVector,
      graphAttrKeys = graphAttrKeys.toVector
    )

end AttrResolver
