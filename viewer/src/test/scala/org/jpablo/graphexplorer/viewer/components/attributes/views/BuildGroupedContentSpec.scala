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
    assertEquals(result.length, 2, "Should have 2 groups")
    assertEquals(result(0)._1, Some(header1), "First group should have header1")
    assertEquals(result(0)._2.length, 2, "First group should have 2 attributes")
    assertEquals(result(0)._2(0), attr1, "First attribute in first group should be attr1")
    assertEquals(result(0)._2(1), attr2, "Second attribute in first group should be attr2")
    assertEquals(result(1)._1, Some(header2), "Second group should have header2")
    assertEquals(result(1)._2.length, 2, "Second group should have 2 attributes")
    assertEquals(result(1)._2(0), attr3, "First attribute in second group should be attr3")
    assertEquals(result(1)._2(1), attr4, "Second attribute in second group should be attr4")
  }

  test("buildGroupedContent should handle empty input") {
    val result = buildGroupedContent(Seq.empty)
    assertEquals(result.length, 0, "Result should be empty for empty input")
  }

  test("buildGroupedContent should handle input with only headers (no attributes)") {
    val header1 = createHeader("Header 1")
    val header2 = createHeader("Header 2")

    val rows = Seq(header1, header2)
    val result = buildGroupedContent(rows)

    assertEquals(result.length, 0, "Should have 0 groups")
  }

  test("buildGroupedContent should handle input with only attributes (no headers)") {
    val attr1 = createInputAttr("attr1")
    val attr2 = createInputAttr("attr2")

    val rows = Seq(attr1, attr2)
    val result = buildGroupedContent(rows)

    assertEquals(result.length, 1, "Should have 1 group")
    assertEquals(result(0)._1, None, "Group should have no header")
    assertEquals(result(0)._2.length, 2, "Group should have 2 attributes")
    assertEquals(result(0)._2(0), attr1, "First attribute should be attr1")
    assertEquals(result(0)._2(1), attr2, "Second attribute should be attr2")
  }

  test("buildGroupedContent should maintain order of attributes within groups") {
    val header = createHeader("Header")
    val attr1 = createInputAttr("attr1")
    val attr2 = createInputAttr("attr2")
    val attr3 = createInputAttr("attr3")

    val rows = Seq(header, attr1, attr2, attr3)
    val result = buildGroupedContent(rows)

    assertEquals(result.length, 1, "Should have 1 group")
    assertEquals(result(0)._1, Some(header), "Group should have header")
    val attrGroup = result(0)._2
    assertEquals(attrGroup.length, 3, "Group should have 3 attributes")
    assertEquals(attrGroup(0), attr1, "First attribute should be attr1")
    assertEquals(attrGroup(1), attr2, "Second attribute should be attr2")
    assertEquals(attrGroup(2), attr3, "Third attribute should be attr3")
  }

  test("buildGroupedContent should handle multiple headers without attributes between them") {
    val header1 = createHeader("Header 1")
    val header2 = createHeader("Header 2")
    val header3 = createHeader("Header 3")
    val attr1 = createInputAttr("attr1")

    val rows = Seq(header1, header2, header3, attr1)
    val result = buildGroupedContent(rows)

    assertEquals(result.length, 1, "Should have 1 group")
    assertEquals(result(0)._1, Some(header3), "Group should have header3")
    assertEquals(result(0)._2.length, 1, "Group should have 1 attribute")
    assertEquals(result(0)._2(0), attr1, "Attribute should be attr1")
  }
}
