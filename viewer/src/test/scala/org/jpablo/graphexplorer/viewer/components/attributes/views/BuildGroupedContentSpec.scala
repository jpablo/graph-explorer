package org.jpablo.graphexplorer.viewer.components.attributes.views

import com.raquo.airstream.core.Signal
import com.raquo.airstream.state.Var
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow
import org.jpablo.graphexplorer.viewer.components.attributes.rows.AttributeRow.{AttributeHeader, InputAttribute}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Layout
import org.jpablo.graphexplorer.viewer.models.{AttributeId, AttrStatus}
import org.jpablo.graphexplorer.viewer.models.AttrStatus.Missing
import org.jpablo.graphexplorer.viewer.widgets.InputType

class BuildGroupedContentSpec extends FunSuite {

  // Helper function to create an AttributeHeader
  def createHeader(title: String): AttributeHeader =
    AttributeHeader(title)

  // Helper function to create InputAttribute with minimum required fields
  def createInputAttr(id: String): InputAttribute =
    InputAttribute(
      attrId = AttributeId(id),
      label = s"Label for $id",
      placeholder = s"Placeholder for $id",
      inputType = InputType.text,
      inputVar = Var(Missing),
      default = Signal.fromValue(""),
      validLayouts = Set(Layout.dot),
      hidden = Signal.fromValue(false)
    )

  test("buildGroupedContent should group attributes under their respective headers") {
    // Arrange
    val header1 = createHeader("Header 1")
    val header2 = createHeader("Header 2")
    val attr1 = createInputAttr("attr1")
    val attr2 = createInputAttr("attr2")
    val attr3 = createInputAttr("attr3")
    val attr4 = createInputAttr("attr4")

    val rows = Seq(
      header1,
      attr1,
      attr2,
      header2,
      attr3,
      attr4
    )

    // Act
    val result = buildGroupedContent(rows)

    // Assert
    assertEquals(result.length, 2)
    val Seq((firstGroup, firstAttrs), (secondGroup, secondAttrs)) = result
    assertEquals(firstGroup, Some(header1))
    assertEquals(firstAttrs, Seq(attr1, attr2))
    assertEquals(secondGroup, Some(header2))
    assertEquals(secondAttrs, Seq(attr3, attr4))
  }

  test("buildGroupedContent should handle empty input") {
    val result = buildGroupedContent(Seq.empty)
    assert(result.isEmpty)
  }

  test("buildGroupedContent should handle input with only headers (no attributes)") {
    val header1 = createHeader("Header 1")
    val header2 = createHeader("Header 2")

    val rows = Seq(header1, header2)
    val result = buildGroupedContent(rows)

    assert(result.isEmpty)
  }

  test("buildGroupedContent should handle input with only attributes (no headers)") {
    val attr1 = createInputAttr("attr1")
    val attr2 = createInputAttr("attr2")

    val rows = Seq(attr1, attr2)
    val result = buildGroupedContent(rows)

    assertEquals(result.length, 1)
    val Seq((headerOpt, attrs)) = result
    assertEquals(headerOpt, None)
    assertEquals(attrs, Seq(attr1, attr2))
  }

  test("buildGroupedContent should maintain order of attributes within groups") {
    val header = createHeader("Header")
    val attr1 = createInputAttr("attr1")
    val attr2 = createInputAttr("attr2")
    val attr3 = createInputAttr("attr3")

    val rows = Seq(header, attr1, attr2, attr3)
    val result = buildGroupedContent(rows)

    assertEquals(result.length, 1)
    val Seq((headerOpt, attrs)) = result
    assertEquals(headerOpt, Some(header))
    assertEquals(attrs, Seq(attr1, attr2, attr3))
  }

  test("buildGroupedContent should handle multiple headers without attributes between them") {
    val header1 = createHeader("Header 1")
    val header2 = createHeader("Header 2")
    val header3 = createHeader("Header 3")
    val attr1 = createInputAttr("attr1")

    val rows = Seq(header1, header2, header3, attr1)
    val result = buildGroupedContent(rows)

    assertEquals(result.length, 1)
    val Seq((headerOpt, attrs)) = result
    assertEquals(headerOpt, Some(header3))
    assertEquals(attrs, Seq(attr1))
  }
}
