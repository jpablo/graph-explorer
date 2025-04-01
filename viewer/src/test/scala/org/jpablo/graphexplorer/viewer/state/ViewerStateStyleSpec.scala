package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.Var
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget, DotAST, toViewerGraphElements}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Missing, Single}
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.widgets.InputType.checkbox

class ViewerStateStyleSpec extends FunSuite:

  val trueAttr  = AttrValue(true.toString)
  val falseAttr = AttrValue(false.toString)

  // Wire InputAttribute rows for filled and bold styles + helper methods to set the values
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

  // TestRows + helper methods to inspect local and root attributes and AST styles
  case class NodeStyleControls(
      state:    ViewerState,
      updates:  Var[AttributesUpdates],
      defaults: Option[Signal[Attributes]] = None
  ) extends TestRows(updates, defaults, state.owner):

    def graph: ViewerGraph = state.fullGraph.now()
    def ast: DotAST        = state.sourceFlow.visibleAST.observe().now()

    def getNodeDefaultAttrs: Map[AttributeId, AttrValue] =
      graph.getDefaultAttributes(AttributeTarget.node).values

    def getNodeAttrs =
      graph.nodes.keys.map(graph.getAttributesById).map(_.values).toList

//    def getDOTGroupNodeStyle: Option[String] =
//      val elements = ast.toViewerGraphElements
//      elements.groups.head.nodeAttrs.get(Style.attrId).map(_.value.toString)

    def getDOTNodesStyles: List[String] =
      val elements = ast.toViewerGraphElements
      elements.nodes.flatMap(_._2.attributes.get(Style.attrId).map(_.value.toString)).toList

  test("[Defaults] InvisibleStyle=true") {
    val state        = ViewerState(ProjectId("test"), _ => (), "")
    val rootUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
    val rootControls = NodeStyleControls(state, rootUpdates)

    // --- Initial state ---
    assertEquals(rootControls.getNodeDefaultAttrs, Attributes.empty.values)
//    assertEquals(rootControls.getDOTGroupNodeStyle, None)

    rootControls.setInvisible(Some(true))
    assertEquals(rootControls.resetInvisibleIsVisible, true, "value different from hardcoded default, so should be reset-able")
    assertEquals(rootControls.getNodeDefaultAttrs, Attributes.of(InvisibleStyle -> true).values)
//    assertEquals(rootControls.getDOTGroupNodeStyle.get, s"${Style.invis}")
  }

  test("[Defaults] borderStyle=dashed,InvisibleStyle=true -> borderStyle=dotted -> InvisibleStyle=false -> borderStyle=solid") {
    val state        = ViewerState(ProjectId("test"), _ => (), "")
    val rootUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
    val rootControls = NodeStyleControls(state, rootUpdates)

    rootControls.setBorder(Some(BorderStyle.dashed))
    rootControls.setInvisible(Some(true))
    assertEquals(rootControls.getNodeDefaultAttrs, Attributes.of(BorderStyle -> BorderStyle.dashed, InvisibleStyle -> true).values)
//    assertEquals(rootControls.getDOTGroupNodeStyle.get, s"${Style.invis},${BorderStyle.dashed}")

    rootControls.setBorder(Some(BorderStyle.dotted))
    assertEquals(rootControls.getNodeDefaultAttrs, Attributes.of(BorderStyle -> BorderStyle.dotted, InvisibleStyle -> true).values)
//    assertEquals(rootControls.getDOTGroupNodeStyle.get, s"${Style.invis},${BorderStyle.dotted}")

    rootControls.setInvisible(Some(false))
    assertEquals(rootControls.getNodeDefaultAttrs, Attributes.of(BorderStyle -> BorderStyle.dotted, InvisibleStyle -> false).values)
//    assertEquals(rootControls.getDOTGroupNodeStyle.get, s"${BorderStyle.dotted}")

    rootControls.setBorder(Some(BorderStyle.solid))
    assertEquals(rootControls.getNodeDefaultAttrs, Attributes.of(BorderStyle -> BorderStyle.solid, InvisibleStyle -> false).values)
//    assertEquals(rootControls.getDOTGroupNodeStyle, None)
  }

  test("[Defaults] borderStyle=dashed,InvisibleStyle=true -> borderStyle=x") {
    val state        = ViewerState(ProjectId("test"), _ => (), "")
    val rootUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
    val rootControls = NodeStyleControls(state, rootUpdates)

    rootControls.setBorder(Some(BorderStyle.dashed))
    rootControls.setInvisible(Some(true))
//    assertEquals(rootControls.getDOTGroupNodeStyle.get, s"${Style.invis},${BorderStyle.dashed}")

    // resetting an attribute removes it from the attributes Map
    rootControls.setBorder(None)
    assertEquals(rootControls.getNodeDefaultAttrs, Map(InvisibleStyle.attrId -> trueAttr))
//    assertEquals(rootControls.getDOTGroupNodeStyle.get, s"${Style.invis}")
  }

  test("Empty defaults, verify that local is false") {
    val state   = ViewerState(ProjectId("test"), _ => (), "")
    given Owner = state.owner
    state.addNodeWithSmartConnection()

    val rootUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
    val localUpdates = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))

    val defaults = Some(state.defaults(AttributeTarget.node))

    val rootControls  = NodeStyleControls(state, rootUpdates)
    val localControls = NodeStyleControls(state, localUpdates, defaults)

//    assertEquals(rootControls.getDOTGroupNodeStyle, None)
//    assertEquals(localControls.getDOTGroupNodeStyle, None)

    assertEquals(
      localControls.getInvisible,
      FillStyle.default,
      "When defaults are empty, local should be the hardcoded default"
    )

  }

  test("[Defaults] InvisibleStyle=true [Locals] invisible should be true") {
    val state = ViewerState(ProjectId("test"), _ => (), "")
//    given Owner = state.owner
    state.addNodeWithSmartConnection()

    val rootUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
    val localUpdates = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))

    val nodeDefaults = Some(state.defaults(AttributeTarget.node))

    val rootControls  = NodeStyleControls(state, rootUpdates)
    val localControls = NodeStyleControls(state, localUpdates, nodeDefaults)

    // --- preparation: set default invisible to true ---
    rootControls.setInvisible(Some(true))
//    assertEquals(rootControls.getDOTGroupNodeStyle.get, "invis")

    // --- test ---
    assertEquals(localControls.getInvisible, true, "When defaults are true, local should start as true")
  }

  test("[Defaults] InvisibleStyle=true [Locals] InvisibleStyle=false and then InvisibleStyle=true") {
    val state = ViewerState(ProjectId("test"), _ => (), "")
    state.addNodeWithSmartConnection()

    val rootUpdates   = state.defaultAttributesUpdates(AttributeTarget.node)
    val localUpdates  = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))
    val defaults      = Some(state.defaults(AttributeTarget.node))
    val rootControls  = NodeStyleControls(state, rootUpdates)
    val localControls = NodeStyleControls(state, localUpdates, defaults)

    // --- preparation: set default filled to true ---
    rootControls.setInvisible(Some(true))
//    assertEquals(rootControls.getDOTGroupNodeStyle, Some("invis"))

    // --- verify ---
    assertEquals(localControls.resetInvisibleIsVisible, false, "no local style yet, so there's nothing to reset")
    assertEquals(localControls.getInvisible, true, "no local style yet so the root value should be used")

    // -- action: local transition true -> false
    localControls.setInvisible(Some(false))

    // --- verify ---
//    assertEquals(localControls.getDOTGroupNodeStyle, Some("invis"), "Group style should not change")
    // local style="" is the only way to override the default style="filled"
    assertEquals(localControls.getDOTNodesStyles, List(""), "Local style should be set to an empty string")

    assertEquals(localControls.getInvisible, false, "after unchecking local, the value should be false")
    assertEquals(localControls.resetInvisibleIsVisible, true, "local is different from root, so it should be reset-able")

    // -- action: local transition false -> true
    localControls.setInvisible(Some(true))
    // --- verify ---
    // at this point local and default styles are the same so the local style should be removed
    assertEquals(localControls.getDOTNodesStyles, Nil, "Local DOT style should not be present")
    assertEquals(localControls.resetInvisibleIsVisible, false, "no local style yet, so there's nothing to reset")
    assertEquals(localControls.getInvisible, true, "no local style yet so the root value should be used")
  }

  test("[Defaults] InvisibleStyle=true [Locals] borderStyle=dotted") {
    val state   = ViewerState(ProjectId("test"), _ => (), "")
    given Owner = state.owner
    state.addNodeWithSmartConnection()

    val rootUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
    val localUpdates = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))

    val defaults = Some(state.defaults(AttributeTarget.node))

    val rootControls  = NodeStyleControls(state, rootUpdates)
    val localControls = NodeStyleControls(state, localUpdates, defaults)

    // --- preparation: set default filled to true ---
    rootControls.setInvisible(Some(true))
//    assertEquals(rootControls.getDOTGroupNodeStyle.get, "invis")

    // --- verify ---
    assertEquals(localControls.resetInvisibleIsVisible, false, "no local style yet, so there's nothing to reset")
    assertEquals(localControls.getInvisible, true, "no local style yet so the root value should be used")
    assertEquals(localControls.getBorder, BorderStyle.default, "hard-coded default value")

    // -- action: set border to dotted
    localControls.setBorder(Some(BorderStyle.dotted))

    // --- verify ---
//    assertEquals(localControls.getDOTGroupNodeStyle.get, "invis", "Group style should not change")
    // to set local bold but keep default filled, we need to set local style="filled,bold"
    assertEquals(localControls.getDOTNodesStyles.head, "invis,dotted", "Local style should combine local and default styles")
  }
