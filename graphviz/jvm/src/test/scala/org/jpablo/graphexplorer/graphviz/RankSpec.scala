package org.jpablo.graphexplorer.graphviz

import munit.FunSuite
import org.jpablo.graphexplorer.graphviz.layout.Rank

/** M2 exit gate: the ported cycle-break + rank assignment must reproduce the
  * oracle's rank partition exactly. Graphviz emits no integer ranks, so the
  * expected partition is recovered from node positions in the `dot` golden:
  * nodes sharing a rank share the rank-axis coordinate (y for TB/BT, x for
  * LR/RL); distinct coordinates ordered along the axis give ranks 0..k.
  *
  * Clustered `03` is excluded — cluster ranking + `rank=same` are M6.
  */
class RankSpec extends FunSuite:

  private val NodeStanza =
    """(?m)^\t+("(?:[^"\\]|\\.)*"|[A-Za-z_][A-Za-z0-9_]*)\t\[([^\]]*)\]""".r
  private val PosRe = """\bpos="([-0-9.]+),([-0-9.]+)"""".r
  private val Keywords = Set("graph", "node", "edge")

  private def unquote(s: String): String =
    if s.startsWith("\"") && s.endsWith("\"") then
      s.substring(1, s.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
    else s

  /** Oracle rank partition: list of node-sets ordered rank 0..k. */
  private def expectedPartition(name: String): List[Set[String]] =
    val dot     = OracleHarness.golden(name, "dot")
    val rankdir = """rankdir=([A-Z]+)""".r.findFirstMatchIn(dot).map(_.group(1)).getOrElse("TB")
    val pts =
      NodeStanza.findAllMatchIn(dot).flatMap { m =>
        val nm = unquote(m.group(1))
        if Keywords.contains(nm) then None
        else PosRe.findFirstMatchIn(m.group(2)).map { p =>
          (nm, p.group(1).toDouble, p.group(2).toDouble)
        }
      }.toList
    // axis coordinate + direction so rank 0 is the first rank along flow
    val axis: ((String, Double, Double)) => Double = rankdir match
      case "LR" => t => t._2
      case "RL" => t => -t._2
      case "BT" => t => t._3
      case _    => t => -t._3 // TB: larger y = earlier rank
    pts
      .groupBy(t => math.round(axis(t) * 10.0))
      .toList
      .sortBy(_._1)
      .map { case (_, group) => group.map(_._1).toSet }

  private def ourPartition(name: String): List[Set[String]] =
    val g = OracleHarness.corpusGraph(name)
    Rank.assign(g).groupBy(_._2).toList.sortBy(_._1).map { case (_, m) => m.keySet }

  List("01-minimal", "02-attrs", "04-ports-compass", "05-strings-comments", "06-undirected")
    .foreach { name =>
      test(s"$name: rank partition matches the oracle"):
        assertEquals(ourPartition(name), expectedPartition(name))
    }

  test("05: cycle node-one→n2→n3→node-one is broken (3 distinct ranks)"):
    val ranks = ourPartition("05-strings-comments")
    assertEquals(ranks.size, 3)
    assertEquals(ranks.head, Set("node one")) // back edge n3→"node one" reversed

  test("05: edge labels ⇒ edgelabel_ranks doubling (real ranks spaced by 2)"):
    val g = OracleHarness.corpusGraph("05-strings-comments")
    assert(Rank.hasEdgeLabel(g), "05 has labelled edges")
    val r = Rank.assign(g)
    // node one=0, n2=2, n3=4 — `rank.c` edgelabel_ranks reserves an odd
    // label rank between each real-node pair (ED_minlen*=2). The golden
    // edge `pos` (7/7/10 ctrl pts ⇒ paths through the label ranks)
    // independently confirms Graphviz uses exactly this doubled structure.
    assertEquals(Set(r("node one"), r("n2"), r("n3")), Set(0, 2, 4))
    // contrast: 01 has no edge labels ⇒ unit rank spacing (no doubling).
    val g1 = OracleHarness.corpusGraph("01-minimal")
    assert(!Rank.hasEdgeLabel(g1))
    val r1 = Rank.assign(g1)
    assertEquals(Set(r1("a"), r1("b"), r1("c")), Set(0, 1, 2))

  test("06: undirected mesh ranks a<b<c<d after cycle breaking"):
    val g = OracleHarness.corpusGraph("06-undirected")
    val r = Rank.assign(g)
    assert(r("a") < r("b"), r.toString)
    assert(r("b") < r("c"), r.toString)
    assert(r("c") < r("d"), r.toString)

  test("03 clusters: ranks computed for all nodes (03's own goldens are gv's bug; gated vs 03b in ClusterSpec)"):
    val g = OracleHarness.corpusGraph("03-subgraph-cluster")
    val r = Rank.assign(g)
    assertEquals(r.keySet, g.nodes.map(_.id).toSet)
    assertEquals(r("start"), 0) // only top-level source

  test("191: every node lands on gv's rank (dot1 acyclic seeds in DECOMPOSE order)"):
    // 191 is still a corpus deferral (its within-rank ORDER differs), so the
    // byte-exact gate can't cover its ranks. The rank axis under rankdir=LR
    // is the golden's x, so the check is exact and needs no rank numbering:
    // group the nodes by their golden x, and the port must group them
    // identically. Guards the dot1 cluster-interior acyclic seed order —
    // gv runs decompose(subg,0) BEFORE acyclic(subg), and seeding in
    // declaration order instead reversed the other back edge of
    // SignatureLayoutType -> FieldSpecType -> SignatureLayoutCompanion,
    // putting RuntimeContext and FieldSpecType each one rank off.
    val name = "191-scala-type-graph"
    val g    = OracleHarness.corpusGraph(name)
    val gold = ujson.read(OracleHarness.golden(name, "json0"))("objects").arr.iterator
      .flatMap(o => o.obj.get("pos").map(p => o("name").str -> p.str.split(",")(0))).toMap
    val ours = Rank.assign(g)
    def partition[A](m: Map[String, A]): Set[Set[String]] =
      m.groupBy(_._2).valuesIterator.map(_.keySet).toSet
    assertEquals(gold.size, 32)
    assertEquals(partition(ours.view.filterKeys(gold.contains).toMap), partition(gold))

end RankSpec
