package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.Var
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget, DotAST, toFlattenedElements}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{BoldStyle, FillStyle, Layout, Style}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Missing, Single}
import org.jpablo.graphexplorer.viewer.models.{AttributeId, Attributes, AttributesUpdates, ElementId, ElementIds}
import org.jpablo.graphexplorer.viewer.widgets.InputType.checkbox

class ViewerStateStyleSpec extends FunSuite:

  val trueAttr = AttrValue(true.toString)
  val falseAttr = AttrValue(false.toString)

  // Wire InputAttribute rows for filled and bold styles + helper methods to set the values
  class TestRows(
      updates:  Var[AttributesUpdates],
      defaults: Option[Signal[Attributes]],
      owner:    Owner
  ):
    given Owner = owner
    val builder = RowBuilder(updates, Signal.fromValue(Layout.dot), defaults)
    val filledRow = builder.row(FillStyle, checkbox)
    val boldRow = builder.row(BoldStyle, checkbox)

    def getFilled: Boolean =
      filledRow.combineDefaultBoolean.observe().now()

    def resetFilledIsVisible: Boolean =
      filledRow.isChanged.observe().now()

    def setBold(value: Option[Boolean]): Unit =
      boldRow.inputVar.set:
        value.fold(Missing)(b => Single(AttrValue(b.toString)))

    def getBold: Boolean =
      boldRow.combineDefaultBoolean.observe().now()

  // TestRows + helper methods to inspect local and root attributes and AST styles
  case class NodeStyleControls(
      state:    ViewerState,
      updates:  Var[AttributesUpdates],
      defaults: Option[Signal[Attributes]] = None
  ) extends TestRows(updates, defaults, state.owner):

    def graph: ViewerGraph = state.fullGraph.now()
    def ast: DotAST = state.sourceFlow.visibleAST.observe().now()

    def getNodeRootAttrs: Map[AttributeId, AttrValue] =
      graph.getRootAttributes(AttributeTarget.node).values

    def getNodeAttrs =
      graph.nodes.keys.map(graph.getAttributesById).map(_.values).toList

    def getDOTGroupNodeStyle: Option[String] =
      val elements = ast.toFlattenedElements
      elements.groups.head.nodeAttrs.get(Style.attrId).map(_.value.toString)

    def getDOTNodesStyles: List[String] =
      val elements = ast.toFlattenedElements
      elements.nodes.flatMap(_.attributes.get(Style.attrId).map(_.value.toString))

  test("[Defaults] bold=true".ignore) {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    val rootUpdates = state.rootTargetAttributesUpdates(AttributeTarget.node)
    val rootControls = NodeStyleControls(state, rootUpdates)
//    rootControls.setFilled(Some(true))
    rootControls.setBold(Some(true))

    assertEquals(rootControls.resetFilledIsVisible, true, "value different from hardcoded default, so should be reset-able")

    assertEquals(rootControls.getNodeRootAttrs, Map(FillStyle.attrId -> trueAttr, BoldStyle.attrId -> trueAttr))
    assertEquals(rootControls.getDOTGroupNodeStyle.get, "bold")
  }

  test("[Defaults] filled=true, bold=true -> filled=false -> bold=false".ignore) {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    val rootUpdates = state.rootTargetAttributesUpdates(AttributeTarget.node)
    val rootControls = NodeStyleControls(state, rootUpdates)
//    rootControls.setFilled(Some(true))
    rootControls.setBold(Some(true))

    assertEquals(rootControls.getDOTGroupNodeStyle.get, "filled,bold")

//    rootControls.setFilled(Some(false))

    assertEquals(rootControls.getNodeRootAttrs, Map(FillStyle.attrId -> falseAttr, BoldStyle.attrId -> trueAttr))
    assertEquals(rootControls.getDOTGroupNodeStyle.get, "bold")

    rootControls.setBold(Some(false))
    assertEquals(rootControls.getNodeRootAttrs, Map(FillStyle.attrId -> falseAttr, BoldStyle.attrId -> falseAttr))
    assertEquals(rootControls.getDOTGroupNodeStyle, None)

  }

  test("[Defaults] filled=true, bold=true -> filled=\"\"".ignore) {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    val rootUpdates = state.rootTargetAttributesUpdates(AttributeTarget.node)
    val rootControls = NodeStyleControls(state, rootUpdates)
//    rootControls.setFilled(Some(true))
    rootControls.setBold(Some(true))

    assertEquals(rootControls.getDOTGroupNodeStyle.get, "filled,bold")

//    rootControls.setFilled(None)
    // resetting an attribute removes it from the attributes Map
    assertEquals(rootControls.getNodeRootAttrs, Map(BoldStyle.attrId -> trueAttr))
    assertEquals(rootControls.getDOTGroupNodeStyle.get, "bold")
  }

  test("Empty defaults, verify that local is false") {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    given Owner = state.owner
    state.addNodeWithSmartConnection()

    val rootUpdates = state.rootTargetAttributesUpdates(AttributeTarget.node)
    val localUpdates = state.elementAttributes(ElementIds(state.allNodeIds()))

    val defaults = Some(state.defaults(AttributeTarget.node))

    val rootControls = NodeStyleControls(state, rootUpdates)
    val localControls = NodeStyleControls(state, localUpdates, defaults)

    assertEquals(rootControls.getDOTGroupNodeStyle, None)
    assertEquals(localControls.getDOTGroupNodeStyle, None)

    assertEquals(
      localControls.getFilled,
      FillStyle.default,
      "When defaults are empty, local should be the hardcoded default"
    )

  }

  test("[Defaults] filled=true [Locals] filled should be true".ignore) {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    given Owner = state.owner
    state.addNodeWithSmartConnection()

    val rootUpdates = state.rootTargetAttributesUpdates(AttributeTarget.node)
    val localUpdates = state.elementAttributes(ElementIds(state.allNodeIds()))

    val defaults = Some(state.defaults(AttributeTarget.node))

    val rootControls = NodeStyleControls(state, rootUpdates)
    val localControls = NodeStyleControls(state, localUpdates, defaults)

    // --- preparation: set default filled to true ---
//    rootControls.setFilled(Some(true))
    assertEquals(rootControls.getDOTGroupNodeStyle.get, "filled")

    // --- test ---
    assertEquals(localControls.getFilled, true, "When defaults are true, local should start as true")

  }

  test("[Defaults] filled=true [Locals] filled=false and then filled=true".ignore) {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    given Owner = state.owner
    state.addNodeWithSmartConnection()

    val rootUpdates = state.rootTargetAttributesUpdates(AttributeTarget.node)
    val localUpdates = state.elementAttributes(ElementIds(state.allNodeIds()))

    val defaults = Some(state.defaults(AttributeTarget.node))

    val rootControls = NodeStyleControls(state, rootUpdates)
    val localControls = NodeStyleControls(state, localUpdates, defaults)

    // --- preparation: set default filled to true ---
//    rootControls.setFilled(Some(true))
    assertEquals(rootControls.getDOTGroupNodeStyle.get, "filled")

    // --- verify ---
    assertEquals(localControls.resetFilledIsVisible, false, "no local style yet, so there's nothing to reset")
    assertEquals(localControls.getFilled, true, "no local style yet so the root value should be used")

    // -- action: local transition true -> false
//    localControls.setFilled(Some(false))

    // --- verify ---
    assertEquals(localControls.getDOTGroupNodeStyle.get, "filled", "Group style should not change")
    // local style="" is the only way to override the default style="filled"
    assertEquals(localControls.getDOTNodesStyles.head, "", "Local style should be set to an empty string")

    assertEquals(localControls.getFilled, false, "after unchecking local, the value should be false")
    assertEquals(localControls.resetFilledIsVisible, true, "local is different from root, so it should be reset-able")

    // -- action: local transition false -> true
//    localControls.setFilled(Some(true))
    // --- verify ---
    // at this point local and default styles are the same so the local style should be removed
    assertEquals(localControls.getDOTNodesStyles, Nil, "Local DOT style should not be present")
    assertEquals(localControls.resetFilledIsVisible, false, "no local style yet, so there's nothing to reset")
    assertEquals(localControls.getFilled, true, "no local style yet so the root value should be used")
  }

  test("[Defaults] filled=true [Locals] click on local bold=true".ignore) {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    given Owner = state.owner
    state.addNodeWithSmartConnection()

    val rootUpdates = state.rootTargetAttributesUpdates(AttributeTarget.node)
    val localUpdates = state.elementAttributes(ElementIds(state.allNodeIds()))

    val defaults = Some(state.defaults(AttributeTarget.node))

    val rootControls = NodeStyleControls(state, rootUpdates)
    val localControls = NodeStyleControls(state, localUpdates, defaults)

    // --- preparation: set default filled to true ---
//    rootControls.setFilled(Some(true))
    assertEquals(rootControls.getDOTGroupNodeStyle.get, "filled")

    // --- verify ---
    assertEquals(localControls.resetFilledIsVisible, false, "no local style yet, so there's nothing to reset")
    assertEquals(localControls.getFilled, true, "no local style yet so the root value should be used")
    assertEquals(localControls.getBold, BoldStyle.default, "hard-coded default value")

    // -- action: set local bold to true
    localControls.setBold(Some(true))

    // --- verify ---
    assertEquals(localControls.getDOTGroupNodeStyle.get, "filled", "Group style should not change")
    // to set local bold but keep default filled, we need to set local style="filled,bold"
    assertEquals(localControls.getDOTNodesStyles.head, "filled,bold", "Local style should combine local and default styles")
  }

