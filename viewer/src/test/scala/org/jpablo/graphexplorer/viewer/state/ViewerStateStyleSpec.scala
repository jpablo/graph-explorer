package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.Var
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.ast.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Missing, Single}
import org.jpablo.graphexplorer.viewer.widgets.InputType.checkbox

class ViewerStateStyleSpec extends FunSuite:

  val trueAttr  = AttrValue(true.toString)
  val falseAttr = AttrValue(false.toString)

  // Wire InputAttribute rows for invis and border styles + helper methods to set the values
  class TestRows(
      updates:  Var[AttributesUpdates],
      defaults: Option[Signal[Attributes]],
      owner:    Owner
  ):
    given Owner      = owner
    val builder      = RowBuilder(updates, Signal.fromValue(Layout.dot), defaults)
    val invisibleRow = builder.row(InvisibleStyle, checkbox)
    val borderRow    = builder.row(BorderStyle, checkbox)

    def resetInvisibleIsVisible: Boolean =
      invisibleRow.isChanged.observe().now()

    def setInvisible(value: Option[Boolean]): Unit =
      invisibleRow.inputVar.set:
        value.fold(Missing)(b => Single(AttrValue(b.toString)))

    def getInvisible: Boolean =
      invisibleRow.combineDefaultBoolean.observe().now()

    def setBorder(value: Option[BorderStyle]): Unit =
      borderRow.inputVar.set:
        value.fold(Missing)(b => Single(AttrValue(b.toString)))

    def getBorder: BorderStyle =
      BorderStyle.valueOf(borderRow.combineDefaultString.observe().now())

  // TestRows + helper methods to inspect element and root attributes and AST styles
  case class NodeStyleControls(
      state:    ViewerState,
      updates:  Var[AttributesUpdates],
      defaults: Option[Signal[Attributes]] = None
  ) extends TestRows(updates, defaults, state.owner):

    def graph: ViewerGraph = state.fullGraph.now()
    def ast: DotAST        = state.sourceFlow.visibleAST.observe().now()

    def getNodeDefaultAttrs: Attributes =
      graph.getDefaultAttributes(AttributeTarget.node)

    def getDOTDefaultStyle(target: "node" | "edge" | "graph"): List[String] =
      ast.children
        .collect:
          case AttrStmt(`target`, attrs) => attrs.find(_.id == Style.attrId.value).map(_.attrEq.toString)
        .flatten

    def getDOTNodeStyles: List[String] =
      ast.children
        .collect:
          case NodeStmt(_, attrs) => attrs.find(_.id == Style.attrId.value).map(_.attrEq.toString)
        .flatten

  test("[Defaults] InvisibleStyle=true") {
    val state           = ViewerState(ProjectId("test"), _ => (), "")
    val defaultUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
    val defaultControls = NodeStyleControls(state, defaultUpdates)

    // --- Initial state ---
    assertEquals(defaultControls.getNodeDefaultAttrs, Attributes.empty)
    assertEquals(defaultControls.getDOTDefaultStyle("node"), Nil)

    defaultControls.setInvisible(Some(true))
    assertEquals(defaultControls.resetInvisibleIsVisible, true, "value different from hardcoded default, so should be reset-able")
    assertEquals(defaultControls.getNodeDefaultAttrs, Attributes.of(InvisibleStyle -> true))
    assertEquals(defaultControls.getDOTDefaultStyle("node"), List("invis"))
  }

  test("[Defaults] borderStyle=dashed,InvisibleStyle=true -> borderStyle=dotted -> InvisibleStyle=false -> borderStyle=solid") {
    val state           = ViewerState(ProjectId("test"), _ => (), "")
    val defaultUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
    val defaultControls = NodeStyleControls(state, defaultUpdates)

    defaultControls.setBorder(Some(BorderStyle.dashed))
    defaultControls.setInvisible(Some(true))
    assertEquals(defaultControls.getNodeDefaultAttrs, Attributes.of(BorderStyle -> BorderStyle.dashed, InvisibleStyle -> true))
    assertEquals(defaultControls.getDOTDefaultStyle("node"), List("invis,dashed"))

    defaultControls.setBorder(Some(BorderStyle.dotted))
    assertEquals(defaultControls.getNodeDefaultAttrs, Attributes.of(BorderStyle -> BorderStyle.dotted, InvisibleStyle -> true))
    assertEquals(defaultControls.getDOTDefaultStyle("node"), List("invis,dotted"))

    defaultControls.setInvisible(Some(false))
    assertEquals(defaultControls.getNodeDefaultAttrs, Attributes.of(BorderStyle -> BorderStyle.dotted, InvisibleStyle -> false))
    assertEquals(defaultControls.getDOTDefaultStyle("node"), List("dotted"))

    defaultControls.setBorder(Some(BorderStyle.solid))
    assertEquals(defaultControls.getNodeDefaultAttrs, Attributes.of(BorderStyle -> BorderStyle.solid, InvisibleStyle -> false))
    assertEquals(defaultControls.getDOTDefaultStyle("node"), Nil)
  }

  test("[Defaults] borderStyle=dashed,InvisibleStyle=true -> borderStyle=x") {
    val state           = ViewerState(ProjectId("test"), _ => (), "")
    val defaultUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
    val defaultControls = NodeStyleControls(state, defaultUpdates)

    defaultControls.setBorder(Some(BorderStyle.dashed))
    defaultControls.setInvisible(Some(true))
    assertEquals(defaultControls.getDOTDefaultStyle("node"), List("invis,dashed"))

    // resetting an attribute removes it from the attributes Map
    defaultControls.setBorder(None)
    assertEquals(defaultControls.getNodeDefaultAttrs, Attributes.of(InvisibleStyle -> true))
    assertEquals(defaultControls.getDOTDefaultStyle("node"), List("invis"))
  }

  test("Empty defaults, verify that element is false") {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    state.addNodeWithSmartConnection()

    val defaultUpdates = state.defaultAttributesUpdates(AttributeTarget.node)
    val elementUpdates = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))

    val defaults = Some(state.defaults(AttributeTarget.node))

    val defaultControls = NodeStyleControls(state, defaultUpdates)
    val elementControls = NodeStyleControls(state, elementUpdates, defaults)

    assertEquals(defaultControls.getDOTDefaultStyle("node"), Nil)
    assertEquals(elementControls.getDOTDefaultStyle("node"), Nil)

    assertEquals(
      elementControls.getInvisible,
      FillStyle.default,
      "When defaults are empty, element should be the hardcoded default"
    )
  }

  test("[Defaults] InvisibleStyle=true [element] invisible should be true") {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    state.addNodeWithSmartConnection()

    val defaultUpdates = state.defaultAttributesUpdates(AttributeTarget.node)
    val elementUpdates = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))

    val defaultControls = NodeStyleControls(state, defaultUpdates)
    val elementControls = NodeStyleControls(state, elementUpdates, defaults = Some(state.defaults(AttributeTarget.node)))

    // --- preparation: set default invisible to true ---
    defaultControls.setInvisible(Some(true))
    assertEquals(defaultControls.getDOTDefaultStyle("node"), List("invis"))

    // --- test ---
    assertEquals(elementControls.getInvisible, true, "When defaults are true, element should start as true")
  }

  test("[Defaults] InvisibleStyle=true [element] InvisibleStyle=false and then InvisibleStyle=true") {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    state.addNodeWithSmartConnection()

    val defaultUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
    val elementUpdates  = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))
    val defaultControls = NodeStyleControls(state, defaultUpdates)
    val elementControls = NodeStyleControls(state, elementUpdates, defaults = Some(state.defaults(AttributeTarget.node)))

    // --- preparation: set default invis to true ---
    defaultControls.setInvisible(Some(true))
    assertEquals(defaultControls.getDOTDefaultStyle("node"), List("invis"))

    // --- verify ---
    assertEquals(elementControls.resetInvisibleIsVisible, false, "no element style yet, so there's nothing to reset")
    assertEquals(elementControls.getInvisible, true, "no element style yet so the root value should be used")

    // -- action: element transition true -> false
    elementControls.setInvisible(Some(false))

    // --- verify ---
    assertEquals(elementControls.getDOTDefaultStyle("node"), List("invis"), "Group style should not change")
    // element style="" is the only way to override the default style="invis"
    assertEquals(elementControls.getDOTNodeStyles, List(""), "Element style should be set to an empty string")
    assertEquals(elementControls.getInvisible, false, "after unchecking element, the value should be false")
    assertEquals(elementControls.resetInvisibleIsVisible, true, "element is different from root, so it should be reset-able")

    // -- action: element transition false -> true
    elementControls.setInvisible(Some(true))

    // --- verify ---
    // at this point element and default styles are the same so the element style should be removed
    assertEquals(elementControls.getDOTNodeStyles, Nil, "Element DOT style should not be present")
    assertEquals(elementControls.getNodeDefaultAttrs, Attributes.of(InvisibleStyle -> true))
    assertEquals(elementControls.resetInvisibleIsVisible, false, "no element style yet, so there's nothing to reset")
    assertEquals(elementControls.getInvisible, true, "no element style yet so the root value should be used")
  }

  test("[Defaults] InvisibleStyle=true [element] borderStyle=dotted") {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    state.addNodeWithSmartConnection()

    val defaultUpdates = state.defaultAttributesUpdates(AttributeTarget.node)
    val elementUpdates = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))

    val defaultControls = NodeStyleControls(state, defaultUpdates)
    val elementControls = NodeStyleControls(state, elementUpdates, defaults = Some(state.defaults(AttributeTarget.node)))

    // --- preparation: set default invis to true ---
    defaultControls.setInvisible(Some(true))
    assertEquals(defaultControls.getDOTDefaultStyle("node"), List("invis"))

    // --- verify ---
    assertEquals(elementControls.resetInvisibleIsVisible, false, "no element style yet, so there's nothing to reset")
    assertEquals(elementControls.getInvisible, true, "no element style yet so the root value should be used")
    assertEquals(elementControls.getBorder, BorderStyle.default, "hard-coded default value")

    // -- action: set border to dotted
    elementControls.setBorder(Some(BorderStyle.dotted))

    // --- verify ---
    assertEquals(elementControls.getDOTDefaultStyle("node"), List("invis"), "Group style should not change")
    // to set element bold but keep default invis, we need to set element style="invis,bold"
    assertEquals(elementControls.getDOTNodeStyles, List("invis,dotted"), "Element style should combine element and default styles")
  }
