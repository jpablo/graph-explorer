package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidAttrKeys.*
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Label, Rankdir}
import org.jpablo.graphexplorer.viewer.graph.{ViewerGraph, ViewerGraphElements}
import org.jpablo.graphexplorer.viewer.models.*

import scala.annotation.tailrec

/** True when the graph came from a Mermaid state diagram. */
def isMermaidStateDiagram(graph: ViewerGraph): Boolean =
  graph.elements.graphAttributes.values.get(MermaidDiagramAttr).exists(_.toString == StateDiagramHeader)

/** Converts a ViewerGraph back to Mermaid state diagram text.
  *
  * This is the inverse of `stateDiagramToViewerGraph`. A canvas edit replaces the whole source with this text. Thus it writes each thing
  * that the graph keeps: the states with their labels, descriptions and kinds, the composite states with their states, the transitions with
  * their labels, the direction, the title, the classes and the styles.
  *
  * A state goes in the block of its composite state. A transition goes in the innermost block that holds both of its states, because
  * Mermaid moves a state into each block where it sees it. A transition with a start or end state goes in the block of that state, because
  * `[*]` is the start or end state of the block where it is.
  */
def viewerGraphToMermaidStateText(graph: ViewerGraph): String =
  val lines           = StringBuilder()
  val graphAttributes = graph.elements.graphAttributes

  graphAttributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty).foreach { title =>
    lines.append("---\n")
    lines.append(s"title: $title\n")
    lines.append("---\n")
  }
  lines.append(s"$StateDiagramHeader\n")
  graphAttributes.values.get(Rankdir.attrId).map(_.toString.trim).filter(_.nonEmpty).foreach { direction =>
    lines.append(s"  direction $direction\n")
  }
  // Mermaid reads `classDef`, `class` and `style` at the top level only.
  collectClassDefBodies(graphAttributes).foreach { (className, body) =>
    lines.append(s"  classDef $className $body\n")
  }

  val rootGroupId = GroupId(ViewerGraphElements.defaultRootId.value)
  val groups      = graph.groups.filter((groupId, _) => groupId != rootGroupId)
  val nodeOrder   = graph.nodes.keysIterator.zipWithIndex.toMap

  def parentOf(member: GroupMemberId): Option[GroupId] = graph.memberships.get(member).filter(groups.contains)

  // A transition to a composite state ends at a node with the id of the group.
  // That node is where the group is.
  def scopeOf(nodeId: NodeId): Option[GroupId] =
    val asGroup = GroupId(nodeId.value)
    if groups.contains(asGroup) then parentOf(asGroup) else parentOf(nodeId)

  def kindOf(nodeId: NodeId): MermaidStateKind =
    graph.nodes
      .get(nodeId)
      .flatMap(_.attributes.values.get(MermaidStateTypeAttr))
      .fold(MermaidStateKind.Plain)(v => MermaidStateKind.fromKeyword(v.toString))

  def isComposite(nodeId: NodeId): Boolean = groups.contains(GroupId(nodeId.value))

  // The scope and each scope around it, out to the top level (None).
  def enclosing(scope: Option[GroupId]): List[Option[GroupId]] =
    @tailrec
    def loop(current: Option[GroupId], seen: Set[GroupId], acc: List[Option[GroupId]]): List[Option[GroupId]] =
      current match
        // The `seen` set stops at a membership cycle.
        case Some(groupId) if !seen.contains(groupId) => loop(parentOf(groupId), seen + groupId, current :: acc)
        case _                                        => (None :: acc).reverse
    loop(scope, Set.empty, Nil)

  def transitionScope(arrow: Arrow): Option[GroupId] =
    if kindOf(arrow.source).isPseudo then scopeOf(arrow.source)
    else if kindOf(arrow.target).isPseudo then scopeOf(arrow.target)
    else
      val aroundSource = enclosing(scopeOf(arrow.source))
      enclosing(scopeOf(arrow.target)).find(aroundSource.contains).flatten

  // The source order first: Mermaid lays out the states in the order of the
  // transitions. A new arrow has no DOM id, so it comes after the others.
  def sourceOrder(arrow: Arrow): Int =
    arrow.attributes.values
      .get(MermaidDomIdAttr)
      .map(_.toString)
      .collect { case StateEdgeDomId(n) => n }
      .flatMap(_.toIntOption)
      .getOrElse(Int.MaxValue)

  val orderedArrows = graph.arrows.values.toVector.sortBy { arrow =>
    (
      sourceOrder(arrow),
      nodeOrder.getOrElse(arrow.source, Int.MaxValue),
      nodeOrder.getOrElse(arrow.target, Int.MaxValue),
      arrow.seq,
      arrow.id.value
    )
  }
  val arrowsByScope = orderedArrows.groupBy(transitionScope)
  val nodesByScope  = graph.nodes.keysIterator.toVector.groupBy(scopeOf)

  def firstMember(groupId: GroupId): Int =
    nodesByScope.getOrElse(Some(groupId), Vector.empty).flatMap(nodeOrder.get).minOption.getOrElse(Int.MaxValue)

  val childrenByScope: Map[Option[GroupId], Vector[GroupId]] =
    groups.keysIterator.toVector.groupBy(parentOf).view.mapValues(_.sortBy(g => (firstMember(g), g.value))).toMap

  val emitted = scala.collection.mutable.Set[GroupId]()

  def emitScope(scope: Option[GroupId], depth: Int): Unit =
    val indent    = "  " * depth
    val scoped    = arrowsByScope.getOrElse(scope, Vector.empty)
    val endpoints = scoped.iterator.flatMap(_.endpoints).toSet
    // A declaration comes before the transitions: Mermaid takes the kind of a
    // state (fork, join, choice) from the first statement that names it.
    nodesByScope.getOrElse(scope, Vector.empty).foreach { nodeId =>
      if !isComposite(nodeId) && !kindOf(nodeId).isPseudo then
        graph.nodes.get(nodeId).foreach { node =>
          stateDeclarations(nodeId, node, kindOf(nodeId), named = endpoints.contains(nodeId)).foreach { line =>
            lines.append(s"$indent$line\n")
          }
        }
    }
    childrenByScope.getOrElse(scope, Vector.empty).foreach(emitComposite(_, depth))
    scoped.foreach { arrow =>
      def end(nodeId: NodeId) = if kindOf(nodeId).isPseudo then "[*]" else stateId(nodeId.value)
      val label               = labelText(arrow.attributes).map(descriptionText).filter(_.trim.nonEmpty)
      lines.append(s"$indent${end(arrow.source)} --> ${end(arrow.target)}${label.fold("")(l => s" : $l")}\n")
    }

  def emitComposite(groupId: GroupId, depth: Int): Unit =
    if emitted.add(groupId) then
      val indent = "  " * depth
      val id     = stateId(groupId.value)
      val shown  = groups.get(groupId).flatMap(g => labelText(g.attributes)).getOrElse(groupId.value)
      val head   = if shown != id then s"""state "${quotedText(shown)}" as $id""" else s"state $id"
      lines.append(s"$indent$head {\n")
      emitScope(Some(groupId), depth + 1)
      lines.append(s"$indent}\n")

  emitScope(None, depth = 1)
  // A membership cycle leaves groups that no block reaches. Write them at the
  // top level, so that no group is lost.
  groups.keysIterator.toVector.sortBy(_.value).foreach(emitComposite(_, depth = 1))

  // `class` and `style` take only word characters as ids.
  def emitStyling(rawId: String, attributes: Attributes, css: Option[String]): Unit =
    val id = stateId(rawId)
    if id.forall(c => (c.isLetterOrDigit && c < 128) || c == '_') then
      attributes.values.get(MermaidClassAttr).toVector.flatMap(v => parseMermaidClassNames(v.toString)).foreach { className =>
        lines.append(s"  class $id $className\n")
      }
      css.foreach(body => lines.append(s"  style $id $body\n"))

  graph.nodes.foreach { (nodeId, node) =>
    if !isComposite(nodeId) && !kindOf(nodeId).isPseudo then emitStyling(nodeId.value, node.attributes, nodeStyleDirectiveCss(node))
  }
  groups.toVector.sortBy(_._1.value).foreach { (groupId, group) =>
    emitStyling(groupId.value, group.attributes, groupStyleDirectiveCss(group))
  }

  lines.toString

private val StateEdgeDomId = "edge(\\d+)".r

/** The lines that declare a state: its kind or its label, and its extra descriptions. `named` is true when a transition in the same block
  * names the state. A state with no label and no kind then needs no line.
  */
private def stateDeclarations(nodeId: NodeId, node: ViewerNode, kind: MermaidStateKind, named: Boolean): List[String] =
  val id    = stateId(nodeId.value)
  val shown = labelText(node.attributes).getOrElse(nodeId.value)
  val descriptions =
    node.attributes.values
      .get(MermaidStateDescriptionsAttr)
      .toList
      .flatMap(_.toString.split('\n'))
      .map(_.trim)
      .filter(_.nonEmpty)
  kind match
    case MermaidStateKind.Fork | MermaidStateKind.Join | MermaidStateKind.Choice =>
      // Mermaid shows no text on a fork, join or choice state.
      kind.keyword.toList.map(keyword => s"state $id <<$keyword>>")
    case _ =>
      // Mermaid takes the first description as the label. Thus a state with
      // extra descriptions also needs its label line, or the first extra one
      // becomes the label.
      val labelLine =
        if shown != id || descriptions.nonEmpty then Some(s"""state "${quotedText(shown)}" as $id""")
        else Option.when(!named)(id)
      labelLine.toList ++ descriptions.map(d => s"$id : ${descriptionText(d)}")

/** The label of an element in Mermaid's form, when it has one. */
private def labelText(attributes: Attributes): Option[String] =
  attributes.values.get(Label.attrId).map(_.toString).filter(_.nonEmpty).map(MermaidLabelText.fromStored)

/** Maps an id to a Mermaid state id. A state id in a transition cannot contain a space, `-`, `:` or `{`, so each character that is not a
  * letter, a digit or `_` becomes `_`. A Mermaid id passes unchanged.
  */
private def stateId(id: String): String =
  val safe = id.map(c => if c.isLetterOrDigit || c == '_' then c else '_')
  if safe.isEmpty then "_" else safe

/** The text of `state "..." as Id`: it ends at the next `"`. */
private def quotedText(text: String): String = text.replace("\"", "#quot;")

/** The text after `:` in a transition or a description: it ends at the next `:` or `;`. Mermaid shows the entity codes as the characters.
  */
private def descriptionText(text: String): String =
  // One pass: the `;` of `#58;` is not a character of the text.
  text.flatMap {
    case ':'   => "#58;"
    case ';'   => "#59;"
    case other => other.toString
  }
