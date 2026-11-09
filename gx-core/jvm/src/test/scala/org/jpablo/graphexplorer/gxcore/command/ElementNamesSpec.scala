package org.jpablo.graphexplorer.gxcore.command

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.models.{ArrowId, ElementId, GroupId, NodeId}

/** The names that a person or an agent writes for an element (plan §2.1). */
class ElementNamesSpec extends FunSuite:

  private def names(text: String): ElementNames =
    DiagramText.parseNamed(text).fold(e => fail(e), _.names)

  private def ok(n: ElementNames, name: String): Vector[ElementId] =
    n.resolve(name).fold(e => fail(s"'$name' should resolve, but: $e"), identity)

  private def refused(n: ElementNames, name: String): String =
    n.resolve(name).fold(identity, ids => fail(s"'$name' should be refused, but it gave $ids"))

  private val directed = names(
    """digraph G {
      |  api -> db
      |  api -> cache
      |  web -> api
      |  web -> api
      |  subgraph cluster_svc { api; db }
      |  subgraph clusterB { cache }
      |  subgraph plain { web }
      |}
      |""".stripMargin
  )

  // ------------------------------------------------ nodes

  test("a bare name is the node with that id"):
    assertEquals(ok(directed, "api"), Vector(NodeId("api")))

  // ------------------------------------------------ arrows, directed

  test("src..dst names the arrow from src to dst"):
    assertEquals(ok(directed, "api..db"), Vector(ArrowId("api->db/0")))

  test("src..dst names each arrow from src to dst, in the order of the source"):
    assertEquals(ok(directed, "web..api"), Vector(ArrowId("web->api/2"), ArrowId("web->api/3")))

  test("#n names one of several arrows, from 1"):
    assertEquals(ok(directed, "web..api#1"), Vector(ArrowId("web->api/2")))
    assertEquals(ok(directed, "web..api#2"), Vector(ArrowId("web->api/3")))

  test("#1 is valid also when there is only one arrow"):
    assertEquals(ok(directed, "api..db#1"), Vector(ArrowId("api->db/0")))

  test("#n out of range is an error that says how many arrows there are"):
    val why = refused(directed, "web..api#3")
    assert(why.contains("#3"), why)
    assert(why.contains("2 arrows"), why)
    assert(why.contains("web..api#1") && why.contains("web..api#2"), why)
    assert(refused(directed, "web..api#0").contains("#0"))

  test("in a directed graph, dst..src does not name an arrow from src to dst"):
    val why = refused(directed, "db..api")
    assert(why.contains("no arrow from db to api"), why)

  test("an arrow between nodes that do not exist is an error"):
    assert(refused(directed, "api..nope").contains("api..nope"))

  test("an arrow with ports is an arrow between its nodes"):
    val n = names("digraph { a:p -> b; a -> b:q }")
    assertEquals(ok(n, "a..b"), Vector(ArrowId("a:p->b/0"), ArrowId("a->b:q/1")))
    assertEquals(n.render(ArrowId("a:p->b/0")), "a..b#1")

  // ------------------------------------------------ arrows, undirected

  private lazy val undirected = names("graph { a; b; b -- a; a -- b; c -- a }")

  test("in an undirected graph, a..b and b..a name each arrow between the two nodes"):
    val both = Vector(ArrowId("a->b/0"), ArrowId("b->a/1"))
    assertEquals(ok(undirected, "a..b"), both)
    assertEquals(ok(undirected, "b..a"), both)
    assertEquals(ok(undirected, "a..c"), Vector(ArrowId("c->a/2")))

  test("in an undirected graph, #n counts the arrows in either order"):
    assertEquals(ok(undirected, "b..a#1"), Vector(ArrowId("a->b/0")))
    assertEquals(ok(undirected, "a..b#2"), Vector(ArrowId("b->a/1")))

  test("in an undirected graph, the render keeps the order of the arrow and counts both orders"):
    assertEquals(undirected.render(ArrowId("a->b/0")), "a..b#1")
    assertEquals(undirected.render(ArrowId("b->a/1")), "b..a#2")
    assertEquals(undirected.render(ArrowId("c->a/2")), "c..a")

  // ------------------------------------------------ groups

  test("a DOT group has the name that its source gives it"):
    assertEquals(ok(directed, "cluster_svc"), Vector(GroupId("svc")))
    assertEquals(ok(directed, "clusterB"), Vector(GroupId("B")))
    assertEquals(ok(directed, "plain"), Vector(GroupId("plain")))

  test("the id of a DOT cluster without its prefix is not a bare name"):
    val why = refused(directed, "svc")
    assert(why.contains("svc"), why)

  // ------------------------------------------------ the old forms

  test("node:x, arrow:id and group:id stay valid"):
    assertEquals(ok(directed, "node:api"), Vector(NodeId("api")))
    assertEquals(ok(directed, "arrow:api->db/0"), Vector(ArrowId("api->db/0")))
    assertEquals(ok(directed, "group:svc"), Vector(GroupId("svc")))

  test("group: also takes the name that the source gives the group"):
    assertEquals(ok(directed, "group:cluster_svc"), Vector(GroupId("svc")))

  test("a prefix with an element that does not exist is an error"):
    assert(refused(directed, "node:nope").contains("nope"))
    assert(refused(directed, "arrow:api->nope/7").contains("api->nope/7"))
    assert(refused(directed, "group:nope").contains("nope"))

  test("a prefix with no id is an error"):
    assert(refused(directed, "node:").contains("node:"))

  test("a colon that is not a known prefix is part of a bare name"):
    val n = names("""digraph { "a:b" -> c }""")
    assertEquals(ok(n, "a:b"), Vector(NodeId("a:b")))

  // ------------------------------------------------ errors

  test("a name that names nothing is an error that quotes it"):
    assert(refused(directed, "nope").contains("'nope'"))

  test("the DOT spelling of an arrow gets a hint"):
    val why = refused(directed, "api->db")
    assert(why.contains("api..db"), why)

  test("a node and a group with one name: the bare name is refused, and the error lists both prefixes"):
    val n   = names("digraph { cluster_svc; subgraph cluster_svc { x } }")
    val why = refused(n, "cluster_svc")
    assert(why.contains("node:cluster_svc"), why)
    assert(why.contains("group:cluster_svc"), why)
    assertEquals(ok(n, "node:cluster_svc"), Vector(NodeId("cluster_svc")))
    assertEquals(ok(n, "group:cluster_svc"), Vector(GroupId("svc")))

  test("a node whose name looks like an arrow: the bare name is refused, and the error lists both"):
    val n   = names("""digraph { "a..b"; a -> b }""")
    val why = refused(n, "a..b")
    assert(why.contains("node:a..b"), why)
    assert(why.contains("arrow:a->b/0"), why)

  test("a node whose name has #n: the bare name is refused when it is also an arrow"):
    val n   = names("""digraph { a -> "b#2"; a -> b; a -> b }""")
    val why = refused(n, "a..b#2")
    assert(why.contains("""arrow:a->b#2/0"""), why)
    assert(why.contains("arrow:a->b/2"), why)

  test("resolveAll reports each name that it cannot resolve"):
    directed.resolveAll(Seq("api", "nope", "db..api")) match
      case Right(ids) => fail(s"expected errors, got $ids")
      case Left(why) =>
        assert(why.contains("nope"), why)
        assert(why.contains("db..api"), why)

  test("resolveAll gives the elements of each name, one time each"):
    assertEquals(
      directed.resolveAll(Seq("api", "web..api", "web..api#1", "node:api")),
      Right(Vector(NodeId("api"), ArrowId("web->api/2"), ArrowId("web->api/3")))
    )

  // ------------------------------------------------ render

  test("render gives the node id, src..dst with #k only for several arrows, and the source name of a group"):
    assertEquals(directed.render(NodeId("api")), "api")
    assertEquals(directed.render(ArrowId("api->db/0")), "api..db")
    assertEquals(directed.render(ArrowId("web->api/2")), "web..api#1")
    assertEquals(directed.render(ArrowId("web->api/3")), "web..api#2")
    assertEquals(directed.render(GroupId("svc")), "cluster_svc")
    assertEquals(directed.render(GroupId("B")), "clusterB")
    assertEquals(directed.render(GroupId("plain")), "plain")

  test("render gives a prefix when the short name names more than one element"):
    val n = names("""digraph { "a..b"; a -> b }""")
    assertEquals(n.render(NodeId("a..b")), "node:a..b")
    assertEquals(n.render(ArrowId("a->b/0")), "arrow:a->b/0")

  test("render gives a prefix for a node whose id looks like a prefix"):
    val n = names("""digraph { "node:x"; "group:svc" }""")
    assertEquals(n.render(NodeId("node:x")), "node:node:x")
    assertEquals(n.render(NodeId("group:svc")), "node:group:svc")
    assertEquals(ok(n, "node:node:x"), Vector(NodeId("node:x")))

  test("the lists give each element with its name, in the order of the source"):
    assertEquals(directed.nodes.map(_._2), Vector("api", "db", "cache", "web"))
    assertEquals(directed.arrows.map(_._2), Vector("api..db", "api..cache", "web..api#1", "web..api#2"))
    assertEquals(directed.groups.map(_._2), Vector("cluster_svc", "clusterB", "plain"))

  // ------------------------------------------------ Mermaid

  private lazy val mermaid = names(
    """flowchart LR
      |  subgraph svc [Service]
      |    a --> b
      |  end
      |  a --> b
      |  b --> c
      |""".stripMargin
  )

  test("Mermaid: a node, an arrow, and #n"):
    assertEquals(ok(mermaid, "a"), Vector(NodeId("a")))
    assertEquals(ok(mermaid, "b..c"), Vector(ArrowId("b->c/1")))
    assertEquals(ok(mermaid, "a..b"), Vector(ArrowId("a->b/1"), ArrowId("a->b/2")))
    assertEquals(ok(mermaid, "a..b#2"), Vector(ArrowId("a->b/2")))
    assert(refused(mermaid, "a..b#3").contains("#3"))

  test("Mermaid: a flowchart is directed"):
    assert(refused(mermaid, "c..b").contains("no arrow from c to b"))

  test("Mermaid: a group has its subgraph id"):
    assertEquals(ok(mermaid, "svc"), Vector(GroupId("svc")))
    assertEquals(ok(mermaid, "group:svc"), Vector(GroupId("svc")))
    assertEquals(mermaid.render(GroupId("svc")), "svc")

  test("Mermaid: render"):
    assertEquals(mermaid.render(ArrowId("a->b/1")), "a..b#1")
    assertEquals(mermaid.render(ArrowId("b->c/1")), "b..c")
    assertEquals(mermaid.render(NodeId("c")), "c")

  test("Mermaid: an arrow to a subgraph makes a node and a group with one name"):
    val n   = names("flowchart LR\n  subgraph svc\n    a\n  end\n  a --> svc\n")
    val why = refused(n, "svc")
    assert(why.contains("node:svc") && why.contains("group:svc"), why)
