package org.jpablo.graphexplorer.viewer.backends.graphviz

import munit.FunSuite

/** `/example/<slug>` is a real address, so the slugs have to behave like ones. */
class DotExamplesSpec extends FunSuite:

  test("every example has a distinct slug"):
    // bySlug is a Map: a collision would silently swallow one example, and the
    // symptom would be a card that opens somebody else's diagram.
    val slugs = DotExamples.examples.keys.map(DotExamples.slugFor).toList
    val dupes = slugs.groupBy(identity).collect { case (s, xs) if xs.sizeIs > 1 => s }
    assertEquals(dupes.toList, Nil, "duplicate example slugs")
    assertEquals(DotExamples.bySlug.size, DotExamples.examples.size)

  test("a slug is URL-safe and round-trips to its example"):
    for (name, source) <- DotExamples.examples do
      val slug = DotExamples.slugFor(name)
      assert(slug.nonEmpty, s"empty slug for '$name'")
      assert(
        slug.forall(c => c.isLower && c.isLetterOrDigit || c.isDigit || c == '-'),
        s"slug '$slug' for '$name' is not URL-safe"
      )
      assert(!slug.startsWith("-") && !slug.endsWith("-"), s"slug '$slug' has a dangling separator")
      assertEquals(DotExamples.bySlug.get(slug), Some((name, source)))

  test("punctuation collapses rather than doubling up"):
    assertEquals(DotExamples.slugFor("Mermaid: Microservices"), "mermaid-microservices")
    assertEquals(DotExamples.slugFor("Empty Graph (Graphviz)"), "empty-graph-graphviz")
    assertEquals(DotExamples.slugFor("  spaced  out  "), "spaced-out")
