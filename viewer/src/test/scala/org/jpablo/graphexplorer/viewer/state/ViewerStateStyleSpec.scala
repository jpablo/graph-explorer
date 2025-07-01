package org.jpablo.graphexplorer.viewer.state

import com.raquo.airstream.core.Signal
import com.raquo.airstream.ownership.Owner
import com.raquo.airstream.state.Var
import munit.FunSuite
import org.jpablo.graphexplorer.viewer.components.attributes.rows.RowBuilder
import org.jpablo.graphexplorer.viewer.formats.dot.ast.{AttrValue, AttributeTarget}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Missing, Single}
import org.jpablo.graphexplorer.viewer.utils.TestHelpers
import org.jpablo.graphexplorer.viewer.widgets.InputType.{checkbox, menuWithExtra}

import scala.concurrent.ExecutionContext.Implicits.global

class ViewerStateStyleSpec extends FunSuite with TestHelpers:

  val trueAttr  = AttrValue(true.toString)
  val falseAttr = AttrValue(false.toString)

  // Wire InputAttribute rows for invis and border styles + helper methods to set the values
  class TestRows(
      updates:  Var[AttributeUpdates],
      defaults: Option[Signal[Attributes]],
      owner:    Owner
  ):
    given Owner      = owner
    val builder      = RowBuilder(updates, Signal.fromValue(Layout.dot), defaults)
    val invisibleRow = builder.row(InvisibleStyle, checkbox)
    // Mirror app: enum-style input for border style
    val borderRow = builder.row(BorderStyle, menuWithExtra(3))

    def resetInvisibleIsVisible: Boolean =
      invisibleRow.isChanged.observe.now()

    def setInvisible(value: Option[Boolean]): Unit =
      val resolved = value match
        case None    => Missing
        case Some(b) =>
          // If a defaults signal is available and the chosen value equals the current default,
          // treat this as a reset (Missing) so the element does not carry a redundant attribute.
          defaults
            .flatMap(sig => Some(sig.observe.now()))
            .flatMap(attrs => attrs.get(InvisibleStyle).map(_.toString == "true"))
            .map(defaultVal => if defaultVal == b then Missing else Single(AttrValue(b.toString)))
            .getOrElse(Single(AttrValue(b.toString)))
      invisibleRow.inputVar.set(resolved)

    def getInvisible: Boolean =
      invisibleRow.combineDefaultBoolean.observe.now()

    def setBorder(value: Option[BorderStyle]): Unit =
      borderRow.inputVar.set:
        value.fold(Missing)(b => Single(AttrValue(b.toString)))

    def getBorder: BorderStyle =
      BorderStyle.valueOf(borderRow.combineDefaultString.observe.now())

  // TestRows + helper methods to inspect element and root attributes and AST styles
  case class NodeStyleControls(
      state:    ViewerState,
      updates:  Var[AttributeUpdates],
      defaults: Option[Signal[Attributes]] = None
  ) extends TestRows(updates, defaults, state.owner):

    def graph: ViewerGraph = state.fullGraphNow()

    def getNodeDefaultAttrs: Attributes =
      graph.getDefaultAttributes(AttributeTarget.node)

  test("Defaults: setting InvisibleStyle=true stores default and is resettable") {
    withGraphviz { graphviz =>
      val state           = ViewerState(ProjectId("test"), graphviz)
      val defaultUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
      val defaultControls = NodeStyleControls(state, defaultUpdates)

      // --- Initial state ---
      assertEquals(defaultControls.getNodeDefaultAttrs, Attributes.empty)

      defaultControls.setInvisible(Some(true))
      assertEquals(defaultControls.resetInvisibleIsVisible, true, "value different from hardcoded default, so should be reset-able")
      assertEquals(defaultControls.getNodeDefaultAttrs, Attributes.of(InvisibleStyle -> true))

    }
  }

  test("Defaults: borderStyle and invisible transitions are reflected in combined default style") {
    withGraphviz { graphviz =>

      val state           = ViewerState(ProjectId("test"), graphviz)
      val defaultUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
      val defaultControls = NodeStyleControls(state, defaultUpdates)

      // Set via UI-like rows to ensure merging behavior matches the app
      defaultUpdates.update(_ + (InvisibleStyle.attrId -> AttrValue("true")) + (BorderStyle.attrId -> AttrValue("dashed")))

      // Combined style is computed for DOT export
      val defaultsNow = defaultControls.getNodeDefaultAttrs
      assertEquals(defaultsNow.get(InvisibleStyle), Some(AttrValue("true")))
      assertEquals(defaultsNow.get(BorderStyle).map(_.toString), Some("dashed"))
      val style1 = defaultControls.graph.elements.combineStyleAttributes.defaultNodeAttributes.get(Style).map(_.toString)
      assert(style1.exists(_.contains("invis")))
      assert(style1.exists(_.contains("dashed")))

      defaultUpdates.update(_ + (BorderStyle.attrId -> AttrValue("dotted")))
      val style2 = defaultControls.graph.elements.combineStyleAttributes.defaultNodeAttributes.get(Style).map(_.toString)
      assert(style2.exists(_.contains("invis")))
      assert(style2.exists(_.contains("dotted")))

      defaultUpdates.update(_ + (InvisibleStyle.attrId -> AttrValue("false")))
      val style3 = defaultControls.graph.elements.combineStyleAttributes.defaultNodeAttributes.get(Style).map(_.toString)
      assert(style3.exists(_.contains("dotted")))
      assert(style3.forall(!_.contains("invis")))

      defaultUpdates.update(_ + (BorderStyle.attrId -> AttrValue("solid")))
      val style4 = defaultControls.graph.elements.combineStyleAttributes.defaultNodeAttributes.get(Style).map(_.toString)
      assertEquals(style4, Some("solid"))
    }
  }

  test("Defaults: resetting borderStyle removes it while keeping invisible default") {
    withGraphviz { graphviz =>
      val state           = ViewerState(ProjectId("test"), graphviz)
      val defaultUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
      val defaultControls = NodeStyleControls(state, defaultUpdates)

      // Drive via lens so defaults apply immediately in tests
      defaultUpdates.update(_
        + (BorderStyle.attrId    -> AttrValue("dashed"))
        + (InvisibleStyle.attrId -> AttrValue("true")))

      val attrs1 = defaultControls.getNodeDefaultAttrs
      assert(attrs1.get(InvisibleStyle).contains(AttrValue("true")))
      assert(attrs1.get(BorderStyle).contains(AttrValue("dashed")))

      // resetting an attribute removes it from the attributes Map
      defaultUpdates.update(_ + (BorderStyle.attrId -> Missing))
      val attrs2 = defaultControls.getNodeDefaultAttrs
      assert(attrs2.get(InvisibleStyle).contains(AttrValue("true")))
      assert(!attrs2.contains(BorderStyle.attrId))
    }
  }

  test("Elements: with empty defaults, invisible uses hardcoded default (false)") {
    withGraphviz { graphviz =>

      val state = ViewerState(ProjectId("test"), graphviz)
      state.addNodeWithSmartConnection()

      val defaultUpdates = state.defaultAttributesUpdates(AttributeTarget.node)
      val elementUpdates = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))

      val defaults = Some(state.defaults(AttributeTarget.node))

      val defaultControls = NodeStyleControls(state, defaultUpdates)
      val elementControls = NodeStyleControls(state, elementUpdates, defaults)

      // When no style is set on the element, it should use the hardcoded default
      assertEquals(
        elementControls.getInvisible,
        InvisibleStyle.default,
        "When defaults are empty, element should be the hardcoded default"
      )
    }
  }

  test("Elements: inherit InvisibleStyle=true from defaults") {
    withGraphviz { graphviz =>
      val state = ViewerState(ProjectId("test"), graphviz)
      state.addNodeWithSmartConnection()

      val defaultUpdates = state.defaultAttributesUpdates(AttributeTarget.node)
      val elementUpdates = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))

      val defaultControls = NodeStyleControls(state, defaultUpdates)
      val elementControls = NodeStyleControls(state, elementUpdates, defaults = Some(state.defaults(AttributeTarget.node)))

      // --- preparation: set default invisible to true ---
      defaultControls.setInvisible(Some(true))

      // --- test ---
      assertEquals(elementControls.getInvisible, true, "When defaults are true, element should start as true")
    }
  }

  test("Elements: toggling invisible overrides defaults and resets back to inherit") {
    withGraphviz { graphviz =>
      val state = ViewerState(ProjectId("test"), graphviz)
      state.addNodeWithSmartConnection()

      val defaultUpdates  = state.defaultAttributesUpdates(AttributeTarget.node)
      val elementUpdates  = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))
      val defaultControls = NodeStyleControls(state, defaultUpdates)
      val elementControls = NodeStyleControls(state, elementUpdates, defaults = Some(state.defaults(AttributeTarget.node)))

      // --- preparation: set default invis to true ---
      defaultControls.setInvisible(Some(true))

      // --- verify ---
      assertEquals(elementControls.resetInvisibleIsVisible, false, "no element style yet, so there's nothing to reset")
      assertEquals(elementControls.getInvisible, true, "no element style yet so the root value should be used")

      // -- action: element transition true -> false
      elementControls.setInvisible(Some(false))

      // --- verify ---

      // Check that the element has its own style attribute to override the default
      val nodeId          = state.allNodeIds().head
      val nodeStyleExport = state.fullGraphNow().elements.combineStyleAttributes.nodes(nodeId).attributes.get(Style).map(_.toString)
      assertEquals(nodeStyleExport, Some(""), "Element style should be reset in DOT (style=\"\") to override default")
      assertEquals(elementControls.getInvisible, false, "after unchecking element, the value should be false")
      assertEquals(elementControls.resetInvisibleIsVisible, true, "element is different from root, so it should be reset-able")

      // -- action: element transition false -> true
      elementControls.setInvisible(Some(true))

      // --- verify ---
      // at this point element and default styles are the same so the element style should be removed
      val nodeId2   = state.allNodeIds().head
      val nodeAttrs = state.fullGraphNow().nodes(nodeId2).attributes
      assert(!nodeAttrs.contains(InvisibleStyle.attrId), "Element should not have its own invisible attribute when matching default")

      assertEquals(elementControls.getNodeDefaultAttrs.get(InvisibleStyle), Some(AttrValue("true")))
      assertEquals(elementControls.resetInvisibleIsVisible, false, "no element style yet, so there's nothing to reset")
      assertEquals(elementControls.getInvisible, true, "no element style yet so the root value should be used")
    }
  }

  test("Elements: borderStyle=dotted combines with default InvisibleStyle=true") {
    withGraphviz { graphviz =>
      val state = ViewerState(ProjectId("test"), graphviz)
      state.addNodeWithSmartConnection()

      val defaultUpdates = state.defaultAttributesUpdates(AttributeTarget.node)
      val elementUpdates = state.elementAttributesUpdates(ElementIds(state.allNodeIds()))

      val defaultControls = NodeStyleControls(state, defaultUpdates)
      val elementControls = NodeStyleControls(state, elementUpdates, defaults = Some(state.defaults(AttributeTarget.node)))

      // --- preparation: set default invis to true ---
      defaultControls.setInvisible(Some(true))

      // --- verify ---
      assertEquals(elementControls.resetInvisibleIsVisible, false, "no element style yet, so there's nothing to reset")
      assertEquals(elementControls.getInvisible, true, "no element style yet so the root value should be used")
      assertEquals(elementControls.getBorder, BorderStyle.default, "hard-coded default value")

      // -- action: set border to dotted
      elementControls.setBorder(Some(BorderStyle.dotted))

      // --- verify ---
      // Check that the element has both styles combined
      val nodeId    = state.allNodeIds().head
      val nodeAttrs = state.fullGraphNow().nodes(nodeId).attributes

      // The element should have its border style set
      assertEquals(nodeAttrs.get(BorderStyle), Some(AttrValue("dotted")))

      // And should inherit the invisible style from defaults
      assertEquals(elementControls.getInvisible, true, "Should inherit invisible from defaults")
    }
  }
