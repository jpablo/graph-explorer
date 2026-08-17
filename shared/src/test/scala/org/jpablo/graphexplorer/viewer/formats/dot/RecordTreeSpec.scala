package org.jpablo.graphexplorer.viewer.formats.dot

import munit.ScalaCheckSuite
import org.jpablo.graphexplorer.viewer.formats.dot.RecordTree.{Group, Leaf}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll

class RecordTreeSpec extends ScalaCheckSuite:

  // All fields before the first test(): registering a test captures `this`,
  // and -Wsafe-init requires it fully initialized by then.

  val abTree = Group(None, Vector(Leaf(None, "a"), Leaf(None, "b")))

  private val genPort: Gen[Option[String]] =
    Gen.option(Gen.identifier.map(_.take(8)))

  private val genDisplayText: Gen[String] =
    Gen.listOf(
      Gen.frequency(
        8 -> Gen.alphaNumChar,
        2 -> Gen.oneOf('{', '}', '|', '<', '>', '.', ',', '-', '(', ')'),
        2 -> Gen.const(' '),
        1 -> Gen.const('\n')
      )
    ).map(_.mkString)

  private val genCanonicalLeaf: Gen[RecordTree] =
    for
      p <- genPort
      t <- genDisplayText
    yield Leaf(p, RecordTree.storedText(t))

  private def genTree(depth: Int): Gen[RecordTree] =
    if depth <= 0 then genCanonicalLeaf
    else
      Gen.frequency(
        3 -> genCanonicalLeaf,
        1 -> (for
          p  <- genPort
          n  <- Gen.choose(1, 3)
          cs <- Gen.listOfN(n, genTree(depth - 1))
        yield Group(p, cs.toVector))
      )

  private val genRoot: Gen[Group] =
    for
      n  <- Gen.choose(1, 4)
      cs <- Gen.listOfN(n, genTree(2))
    yield Group(None, cs.toVector)

  private val genAnyLabel: Gen[String] =
    Gen.listOf(
      Gen.frequency(
        6 -> Gen.alphaNumChar,
        3 -> Gen.oneOf('{', '}', '|', '<', '>', '\\', ' '),
        1 -> Gen.oneOf('\n', '\t')
      )
    ).map(_.mkString)

  // ── parse ────────────────────────────────────────────────────────────────

  test("parse: ports, fields, outer braces"):
    val root = RecordTree.parse("{<f0> a2 | <f1> c | <f2> d}")
    assertEquals(
      root,
      Group(
        None,
        Vector(
          Group(None, Vector(Leaf(Some("f0"), "a2"), Leaf(Some("f1"), "c"), Leaf(Some("f2"), "d")))
        )
      )
    )

  test("parse: pipes without spaces still separate fields (old parser bug)"):
    val root = RecordTree.parse("{<f0> a|<f1> b}")
    assertEquals(RecordTree.leaves(root).map(_.text), Vector("a", "b"))

  test("parse: escaped pipe stays inside one field"):
    val root = RecordTree.parse("a\\|b")
    assertEquals(root, Group(None, Vector(Leaf(None, "a\\|b"))))

  test("parse: empty fields are preserved positionally"):
    val root = RecordTree.parse("a||b")
    assertEquals(RecordTree.leaves(root).map(_.text), Vector("a", "", "b"))

  test("parse: nested group flips one level down"):
    val root = RecordTree.parse("a | {b | c}")
    assertEquals(
      root,
      Group(None, Vector(Leaf(None, "a"), Group(None, Vector(Leaf(None, "b"), Leaf(None, "c")))))
    )

  test("parse: a group can carry a port"):
    val root = RecordTree.parse("<g>{a | b}")
    assertEquals(root, Group(None, Vector(Group(Some("g"), Vector(Leaf(None, "a"), Leaf(None, "b"))))))

  // ── serialize ────────────────────────────────────────────────────────────

  test("serialize: canonical form is stable on canonical input"):
    val label = "{<f0> Node A | <f1> Node B}"
    assertEquals(RecordTree.serialize(RecordTree.parse(label)), label)

  test("serialize: port with empty text has no trailing space"):
    assertEquals(RecordTree.serialize(Group(None, Vector(Leaf(Some("p"), ""), Leaf(None, "x")))), "<p> | x")

  // ── structural edits ─────────────────────────────────────────────────────

  test("insertSibling after"):
    val (r, p) = RecordTree.insertSibling(abTree, List(0), after = true)
    assertEquals(r, Group(None, Vector(Leaf(None, "a"), Leaf(None, ""), Leaf(None, "b"))))
    assertEquals(p, List(1))

  test("insertSibling before"):
    val (r, p) = RecordTree.insertSibling(abTree, List(1), after = false)
    assertEquals(r, Group(None, Vector(Leaf(None, "a"), Leaf(None, ""), Leaf(None, "b"))))
    assertEquals(p, List(1))

  test("insertSibling: stale path is a no-op"):
    val (r, p) = RecordTree.insertSibling(abTree, List(7), after = true)
    assertEquals(r, abTree)
    assertEquals(p, List(7))

  test("splitCell keeps the port on the inner leaf"):
    val root   = Group(None, Vector(Leaf(Some("p"), "a")))
    val (r, p) = RecordTree.splitCell(root, List(0))
    assertEquals(r, Group(None, Vector(Group(None, Vector(Leaf(Some("p"), "a"), Leaf(None, ""))))))
    assertEquals(p, List(0, 1))

  test("splitCell on a group is a no-op (would flip its contents)"):
    val root = Group(None, Vector(Group(None, Vector(Leaf(None, "a"), Leaf(None, "b")))))
    assertEquals(RecordTree.splitCell(root, List(0))._1, root)

  test("removeCell: a group left with one leaf collapses to the leaf"):
    val root   = RecordTree.parse("a | {b | c}")
    val (r, p) = RecordTree.removeCell(root, List(1, 1))
    assertEquals(r, Group(None, Vector(Leaf(None, "a"), Leaf(None, "b"))))
    assertEquals(p, List(1))

  test("removeCell: a group left with one GROUP is kept (depth parity)"):
    val root = RecordTree.parse("{a | {b | c}}")
    val (r, _) = RecordTree.removeCell(root, List(0, 0))
    assertEquals(RecordTree.serialize(r), "{{b | c}}")

  test("removeCell: collapsing inherits the group's port when the leaf has none"):
    val root   = RecordTree.parse("<p>{x | y}")
    val (r, _) = RecordTree.removeCell(root, List(0, 1))
    assertEquals(r, Group(None, Vector(Leaf(Some("p"), "x"))))

  test("removeCell: emptied groups disappear recursively"):
    val root   = RecordTree.parse("{a} | b")
    val (r, p) = RecordTree.removeCell(root, List(0, 0))
    assertEquals(r, Group(None, Vector(Leaf(None, "b"))))
    assertEquals(p, List(0))

  test("removeCell: the last cell leaves one empty cell"):
    val root   = Group(None, Vector(Leaf(None, "a")))
    val (r, p) = RecordTree.removeCell(root, List(0))
    assertEquals(r, Group(None, Vector(Leaf(None, ""))))
    assertEquals(p, List(0))

  test("setPort sets (trimmed), and clears back"):
    val withPort = RecordTree.setPort(abTree, List(0), Some(" p0 "))
    assertEquals(withPort, Group(None, Vector(Leaf(Some("p0"), "a"), Leaf(None, "b"))))
    assertEquals(RecordTree.setPort(withPort, List(0), None), abTree)

  test("ports collects leaf and group ports"):
    val root = RecordTree.parse("<g>{<f0> a | b} | <f1> c")
    assertEquals(RecordTree.ports(root), Set("g", "f0", "f1"))

  test("setText escapes and canonicalizes"):
    val r = RecordTree.setText(abTree, List(0), "x | {y}  z")
    assertEquals(r, Group(None, Vector(Leaf(None, "x \\| \\{y\\} z"), Leaf(None, "b"))))

  // ── text helpers ─────────────────────────────────────────────────────────

  test("storedText: specials escaped, newlines become \\n, spaces collapse"):
    assertEquals(RecordTree.storedText("a|b"), "a\\|b")
    assertEquals(RecordTree.storedText("a\nb"), "a\\nb")
    assertEquals(RecordTree.storedText("  a   b  "), "a b")
    assertEquals(RecordTree.storedText("a\\"), "a")
    assertEquals(RecordTree.storedText("a\\lb"), "a\\lb")

  test("displayText: specials unescape, \\n becomes a newline, \\l stays"):
    assertEquals(RecordTree.displayText("a\\|b"), "a|b")
    assertEquals(RecordTree.displayText("a\\nb"), "a\nb")
    assertEquals(RecordTree.displayText("a\\lb"), "a\\lb")
    assertEquals(RecordTree.displayText("a\\\\b"), "a\\\\b")

  test("unescapeSpecials keeps \\n (plain labels use the same escape)"):
    assertEquals(RecordTree.unescapeSpecials("a\\nb"), "a\\nb")
    assertEquals(RecordTree.unescapeSpecials("a\\{b\\}"), "a{b}")

  // ── navigation ───────────────────────────────────────────────────────────

  test("leafPaths in field order"):
    val root = RecordTree.parse("a | {b | c} | d")
    assertEquals(RecordTree.leafPaths(root), Vector(List(0), List(1, 0), List(1, 1), List(2)))

  test("nearestLeafPath clamps and descends to a leaf"):
    val root = RecordTree.parse("a | {b | c}")
    assertEquals(RecordTree.nearestLeafPath(root, List(5)), List(1, 0))
    assertEquals(RecordTree.nearestLeafPath(root, List(1, 9)), List(1, 1))
    assertEquals(RecordTree.nearestLeafPath(root, List(1)), List(1, 0))

  test("parentIsLR flips with depth"):
    assertEquals(RecordTree.parentIsLR(List(0), topLR = true), true)
    assertEquals(RecordTree.parentIsLR(List(0, 1), topLR = true), false)
    assertEquals(RecordTree.parentIsLR(List(0, 1, 2), topLR = true), true)

  // ── properties ───────────────────────────────────────────────────────────

  property("parse ∘ serialize = identity on canonical trees"):
    forAll(genRoot): root =>
      assertEquals(RecordTree.parse(RecordTree.serialize(root)), root)

  property("serialize ∘ parse reaches a fixed point after one pass"):
    forAll(genAnyLabel): label =>
      val once = RecordTree.serialize(RecordTree.parse(label))
      assertEquals(RecordTree.serialize(RecordTree.parse(once)), once)

  /** The counterexample the property above found, pinned so it is checked on
    * every run rather than whenever a seed happens to reach it.
    *
    * A dangling trailing backslash used to be dropped without re-trimming, so
    * `"x \"` settled to `"x "` on the first pass and only reached `"x"` on the
    * second. Leaf text is documented to carry unescaped spaces already trimmed,
    * so the first pass was the one that was wrong.
    */
  test("a dangling trailing backslash settles in ONE pass"):
    def once(s: String)  = RecordTree.serialize(RecordTree.parse(s))
    def twice(s: String) = once(once(s))

    for label <- List("x \\", "= \\", "a \\", "ab\\", "a|b \\", "a\\ \\") do
      assertEquals(twice(label), once(label), s"not a fixed point after one pass: [$label]")

    // The first pass now lands where the second used to.
    assertEquals(once("x \\"), "x")
    assertEquals(once("a|b \\"), "a | b")

    // And the trim stops at the text, rather than eating into it: an interior
    // escaped space is the user's and survives as a space.
    assertEquals(once("a\\ b"), "a b")
    assertEquals(once("a\\ b \\"), "a b")
