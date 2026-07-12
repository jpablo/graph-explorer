package org.jpablo.graphexplorer.graphviz

import munit.FunSuite

/** M8 end-to-end differential gate: the *full* pure pipeline reached only
  * through the public `Graphviz.renderFormats` facade — exactly the slice
  * the viewer's flagged backend calls — diffed against the captured viz-js
  * goldens.
  *
  *  - **Must-pass set** (label-free TB: 01/06/07): every requested format
  *    emitted, `status: success`, and `dot_json`/`json0` parse to the same
  *    structure as the golden (graph attrs / node & edge sets exact). This
  *    is the CI-green integration gate.
  *  - **M6-feature corpus** (02 attrs / 03 clusters / 04 ports / 05 edge-
  *    labels): the facade must degrade *gracefully* (return, never throw)
  *    so the viewer's fallback path stays well-defined. Their strict
  *    golden parity is deliberately NOT asserted — it is M6's job
  *    (tracked, PORT.md §5); the classification is printed so the M6
  *    backlog stays data-driven, and this self-flags when M6 lands.
  */
class DifferentialSpec extends FunSuite:

  // 02 (rankdir=LR + rounded/filled boxes + vee arrows + edge label) is now
  // fully byte-exact end-to-end (map_point transform + crow arrow + limitBoxes)
  // ⇒ promoted into the must-pass integration set.
  private val mustPass = List("01-minimal", "02-attrs", "06-undirected", "07-cross")
  private val m6Corpus = List("03-subgraph-cluster", "04-ports-compass", "05-strings-comments")

  private def nodeNames(v: ujson.Value): Set[String] =
    v("objects").arr.iterator.map(_("name").str).toSet

  private def edgePairs(v: ujson.Value): Set[(Int, Int)] =
    v("edges").arr.iterator.map(e => (e("tail").num.toInt, e("head").num.toInt)).toSet

  mustPass.foreach { name =>
    test(s"$name: end-to-end via Graphviz.renderFormats matches the golden"):
      val src = OracleHarness.corpusSource(name)
      val r   = Graphviz.renderFormats(src, Seq("dot_json", "json0", "svg"))
      assertEquals(r.status, "success", r.errors.toString)
      assertEquals(r.output.keySet, Set("dot_json", "json0", "svg"))

      val odj = ujson.read(r.output("dot_json"))
      val gdj = ujson.read(OracleHarness.golden(name, "dot_json"))
      assertEquals(odj("name").str, gdj("name").str, s"$name graph name")
      assertEquals(odj("directed").bool, gdj("directed").bool)
      assertEquals(odj("strict").bool, gdj("strict").bool)
      assertEquals(nodeNames(odj), nodeNames(gdj), s"$name node set")
      assertEquals(edgePairs(odj), edgePairs(gdj), s"$name edge set")

      val oj0 = ujson.read(r.output("json0"))
      assertEquals(nodeNames(oj0), nodeNames(gdj), s"$name json0 node set")
      assert(r.output("svg").startsWith("<?xml ") && r.output("svg").contains("</svg>"),
        s"$name svg well-formed")
  }

  test("M6-feature corpus degrades gracefully (returns, never throws)"):
    m6Corpus.foreach { name =>
      val src = OracleHarness.corpusSource(name)
      val r   = Graphviz.renderFormats(src, Seq("dot_json", "json0", "svg"))
      assert(r.status == "success" || r.status == "failure", s"$name returned a status")
      // Data-driven M6 backlog: structural delta vs golden (informational).
      val verdict =
        if r.status != "success" then s"failure: ${r.errors.map(_.message.take(50)).mkString}"
        else
          val o = ujson.read(r.output("dot_json"))
          val g = ujson.read(OracleHarness.golden(name, "dot_json"))
          val ns = if nodeNames(o) == nodeNames(g) then "nodes=ok" else "nodes=DIFF"
          val es = if edgePairs(o) == edgePairs(g) then "edges=ok" else "edges=DIFF"
          s"success $ns $es (strict golden parity = M6)"
      println(f"M8/M6 $name%-22s $verdict")
    }

end DifferentialSpec
