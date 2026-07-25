package org.jpablo.graphexplorer.viewer.attributes

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.AttributeId

/** `DotAttribute.attrId` derives the DOT attribute name REFLECTIVELY, from the
  * object's simple name (lowercased, `$` stripped). That makes renaming an object
  * a silent rename of the serialized attribute — and the derivation itself depends
  * on `getClass.getSimpleName`, which differs between the JVM and Scala.js.
  *
  * These assertions pin the wire names, so a rename or a platform difference fails
  * here instead of silently producing DOT that Graphviz reads differently.
  */
class AttributeIdDerivationSpec extends FunSuite:

  private val expected: List[(DotAttribute[?], String)] = List(
    // dot_json structural keys — `_gvid` needs the explicit override
    GvId     -> "_gvid",
    Name     -> "name",
    Head     -> "head",
    Tail     -> "tail",
    // promoted to DotAttribute, previously written as string literals
    Margin    -> "margin",
    NoJustify -> "nojustify",
    // a representative sample of the derivation's existing users
    Label    -> "label",
    Shape    -> "shape",
    HeadPort -> "headport",
    TailURL  -> "tailURL",
    TailLp   -> "tail_lp"
  )

  expected.foreach { case (attr, name) =>
    test(s"attrId of ${attr.getClass.getSimpleName} is '$name'"):
      assertEquals(attr.attrId, AttributeId(name))
  }

  test("structural keys do not collide with the arrow attributes named alike"):
    assertNotEquals(Head.attrId, ArrowHead.attrId)
    assertNotEquals(Tail.attrId, ArrowTail.attrId)
