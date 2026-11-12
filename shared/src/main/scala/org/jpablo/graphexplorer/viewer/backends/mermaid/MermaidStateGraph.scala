package org.jpablo.graphexplorer.viewer.backends.mermaid

import org.jpablo.graphexplorer.viewer.models.{Arrow, ArrowId, NodeId}

import scala.collection.immutable.VectorMap
import scala.util.matching.Regex

/** The kind of a state in a Mermaid state diagram. */
enum MermaidStateKind derives CanEqual:
  case Plain, Start, End, Fork, Join, Choice

  /** The value of `MermaidStateTypeAttr`. A plain state has no value. */
  def keyword: Option[String] = Option.when(this != Plain)(toString.toLowerCase)

  /** A start or end state: the source writes it as `[*]`. */
  def isPseudo: Boolean = this == Start || this == End

object MermaidStateKind:
  def fromKeyword(keyword: String): MermaidStateKind =
    values.find(_.keyword.contains(keyword)).getOrElse(Plain)

  /** The kind of a node of Mermaid's layout data, from its shape. */
  def fromShape(shape: String): MermaidStateKind =
    shape match
      case "stateStart" => Start
      case "stateEnd"   => End
      case "fork"       => Fork
      case "join"       => Join
      case "choice"     => Choice
      case _            => Plain

/** A state of a Mermaid state diagram, as Mermaid draws it.
  *
  * @param label
  *   the text that Mermaid shows for the state, in Mermaid's form. None when it is the id.
  * @param descriptions
  *   the description lines after the first one (`Id : text` lines).
  * @param parent
  *   the composite state that holds the state. None for the top level.
  * @param domId
  *   the id of the drawn `g.node`. The viewer uses it to find the drawn state.
  */
case class MermaidState(
    id:           String,
    label:        Option[String] = None,
    descriptions: List[String] = Nil,
    kind:         MermaidStateKind = MermaidStateKind.Plain,
    parent:       Option[String] = None,
    classes:      List[String] = Nil,
    styles:       List[String] = Nil,
    domId:        Option[String] = None
)

/** A composite state (`state Id { ... }`): a group. */
case class MermaidCompositeState(
    id:      String,
    label:   Option[String] = None,
    parent:  Option[String] = None,
    classes: List[String] = Nil,
    styles:  List[String] = Nil
)

/** A transition. `domId` is the id of the drawn path (`edge<N>`). */
case class MermaidTransition(
    start: String,
    end:   String,
    label: Option[String] = None,
    domId: Option[String] = None
)

/** A parsed Mermaid state diagram (`stateDiagram-v2`).
  *
  * The states and the transitions are in the order of Mermaid's layout data, which is the order of the source. A start or end state (`[*]`)
  * is a state with the id `<scope>_start` or `<scope>_end`, where the scope is the composite state around it, or `root`. Mermaid gives them
  * the same ids.
  */
case class MermaidStateGraph(
    states:      List[MermaidState] = Nil,
    composites:  List[MermaidCompositeState] = Nil,
    transitions: List[MermaidTransition] = Nil,
    direction:   Option[String] = None,
    title:       Option[String] = None,
    classDefs:   VectorMap[String, List[String]] = VectorMap.empty
):

  /** Each transition with its sequence number: one more than the count of the earlier transitions with the same two ends. Mermaid has no
    * other name for a transition, so the arrow id is `start->end/seq`.
    */
  def numberedTransitions: List[(MermaidTransition, Int)] =
    transitions
      .foldLeft((Map.empty[(String, String), Int], List.empty[(MermaidTransition, Int)])) { case ((counts, acc), t) =>
        val seq = counts.getOrElse((t.start, t.end), 0) + 1
        (counts.updated((t.start, t.end), seq), (t, seq) :: acc)
      }
      ._2
      .reverse

  def arrowId(transition: MermaidTransition, seq: Int): ArrowId =
    Arrow(NodeId(transition.start), NodeId(transition.end), seq = seq).id

  /** The node id of each drawn state, by the DOM id of its `g.node`. */
  def nodeIdsByDomId: Map[String, NodeId] =
    val compositeIds = composites.map(_.id).toSet
    states.iterator
      .filterNot(s => compositeIds.contains(s.id))
      .flatMap(s => s.domId.map(_ -> NodeId(s.id)))
      .toMap

  /** The arrow id of each drawn transition, by the DOM id of its path. */
  def arrowIdsByDomId: Map[String, ArrowId] =
    numberedTransitions.iterator
      .flatMap((t, seq) => t.domId.map(_ -> arrowId(t, seq)))
      .toMap

object MermaidStateGraph:

  /** A node of the layout data of Mermaid's StateDB (`db.getData().nodes`): a state, a composite state, a note, or a concurrent region.
    *
    * @param descriptions
    *   the description lines after the first one. The first one is `label`.
    */
  case class LayoutNode(
      id:           String,
      label:        String,
      descriptions: List[String] = Nil,
      shape:        String = "rect",
      parentId:     Option[String] = None,
      isGroup:      Boolean = false,
      domId:        Option[String] = None,
      cssClasses:   String = "",
      cssStyles:    List[String] = Nil
  )

  /** An edge of the layout data of Mermaid's StateDB (`db.getData().edges`). */
  case class LayoutEdge(id: String, start: String, end: String, label: String = "", classes: String = "transition")

  /** Classes that Mermaid adds to each state and composite state itself. */
  private val MermaidOwnClasses =
    Set("statediagram-state", "statediagram-cluster", "statediagram-cluster-alt", "statediagram-note")

  /** Builds the diagram from the layout data of Mermaid's StateDB.
    *
    * The layout data is what Mermaid draws. Thus the graph has the same states and transitions as the drawing, and with the same ids.
    *
    * Notes are not part of the graph. A concurrent region (`--`) is not a group: its states belong to the composite state around it.
    * Mermaid gives the last region a random id at each parse, so a start or end state in a region gets the id of the composite state
    * instead.
    */
  def fromLayout(
      nodes:     Seq[LayoutNode],
      edges:     Seq[LayoutEdge],
      direction: Option[String] = None,
      title:     Option[String] = None,
      classDefs: Seq[(String, List[String])] = Nil
  ): MermaidStateGraph =
    val byId                     = nodes.iterator.map(n => n.id -> n).toMap
    def isNote(n:    LayoutNode) = n.shape == "note" || n.shape == "noteGroup"
    def isDivider(n: LayoutNode) = n.shape == "divider"

    // The composite state around a node. The depth limit stops a parent cycle.
    def scope(parentId: Option[String], depth: Int = 0): Option[String] =
      parentId.flatMap(byId.get) match
        case Some(p) if isDivider(p) && depth < byId.size => scope(p.parentId, depth + 1)
        case Some(p) if isDivider(p)                      => None
        case Some(p)                                      => Some(p.id)
        case None                                         => None

    val pseudoIds: Map[String, String] =
      nodes.iterator
        .filterNot(_.isGroup)
        .flatMap { n =>
          MermaidStateKind.fromShape(n.shape) match
            case MermaidStateKind.Start => Some(n.id -> s"${scope(n.parentId).getOrElse("root")}_start")
            case MermaidStateKind.End   => Some(n.id -> s"${scope(n.parentId).getOrElse("root")}_end")
            case _                      => None
        }
        .toMap
    def rename(id: String) = pseudoIds.getOrElse(id, id)

    def ownLabel(n: LayoutNode): Option[String] =
      Some(decodeEntities(n.label)).filter(l => l.nonEmpty && l != n.id)

    val states =
      nodes.iterator
        .filterNot(n => n.isGroup || isNote(n))
        .map { n =>
          val kind  = MermaidStateKind.fromShape(n.shape)
          val plain = kind == MermaidStateKind.Plain
          MermaidState(
            id = rename(n.id),
            // Mermaid shows no text on a start, end, fork, join or choice state.
            label = if plain then ownLabel(n) else None,
            descriptions = if plain then n.descriptions.map(decodeEntities).filter(_.nonEmpty) else Nil,
            kind = kind,
            parent = scope(n.parentId),
            classes = userClasses(n.cssClasses),
            styles = n.cssStyles.filter(_.trim.nonEmpty),
            domId = n.domId
          )
        }
        .distinctBy(_.id)
        .toList

    val composites =
      nodes.iterator
        .filter(n => n.isGroup && !isNote(n) && !isDivider(n))
        .map { n =>
          MermaidCompositeState(
            id = n.id,
            label = ownLabel(n),
            parent = scope(n.parentId),
            classes = userClasses(n.cssClasses),
            styles = n.cssStyles.filter(_.trim.nonEmpty)
          )
        }
        .distinctBy(_.id)
        .toList

    val noteIds = nodes.iterator.filter(isNote).map(_.id).toSet
    val transitions =
      edges.iterator
        .filterNot(e => e.classes.split("\\s+").contains("note-edge") || noteIds(e.start) || noteIds(e.end))
        .map(e => MermaidTransition(rename(e.start), rename(e.end), Some(decodeEntities(e.label).trim).filter(_.nonEmpty), Some(e.id)))
        .toList

    MermaidStateGraph(
      states = states,
      composites = composites,
      transitions = transitions,
      direction = direction.map(_.trim).filter(_.nonEmpty),
      title = title.map(decodeEntities).map(_.trim).filter(_.nonEmpty),
      classDefs = VectorMap.from(classDefs.map((name, styles) => name -> styles.map(_.trim).filter(_.nonEmpty)))
    )

  private def userClasses(cssClasses: String): List[String] =
    cssClasses.split("\\s+").iterator.filter(c => c.nonEmpty && !MermaidOwnClasses.contains(c)).distinct.toList

  // Before it parses, Mermaid replaces an entity code with a placeholder:
  // `#58;` becomes `ﬂ°°58¶ß`, and `#quot;` becomes `ﬂ°quot¶ß`. Its parsed data
  // keeps the placeholder.
  private val EntityPlaceholder = "ﬂ°(°?)(\\w+)¶ß".r

  /** Replaces Mermaid's entity placeholders with the text that they stand for. A numeric code becomes its character, `quot` becomes `"`,
    * and a different named entity becomes its source form again (`#amp;`).
    */
  def decodeEntities(text: String): String =
    EntityPlaceholder.replaceAllIn(
      text,
      m =>
        val name = m.group(2)
        val decoded =
          if m.group(1).nonEmpty then
            name.toIntOption.filter(c => c >= 0 && c <= Character.MAX_CODE_POINT).map(c => String(Character.toChars(c))).getOrElse(
              s"#$name;"
            )
          else if name == "quot" then "\""
          else s"#$name;"
        Regex.quoteReplacement(decoded)
    )
