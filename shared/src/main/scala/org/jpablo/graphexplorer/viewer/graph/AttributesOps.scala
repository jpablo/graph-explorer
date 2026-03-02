package org.jpablo.graphexplorer.viewer.graph

import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidStyleDeclarations
import org.jpablo.graphexplorer.viewer.extensions.in
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.*
import org.jpablo.graphexplorer.viewer.models.*
import org.jpablo.graphexplorer.viewer.models.AttrStatus.{Multiple, Single}
import org.jpablo.graphexplorer.viewer.models.ViewerNode.nodeWithDefaults

import scala.collection.immutable.VectorMap

trait AttributesOps:
  this: ViewerGraph =>

  def withoutUnsupportedFeatures: ViewerGraph =
    this.modify(_.elements.graphAttributes).using(_ - Size.attrId - Overlap.attrId)

  /** Updates attributes for a set of nodes and arrows.
    *
    * This method updates the attributes of the specified nodes and arrows:
    *   1. Separates the input IDs into arrow IDs and node IDs 2. Updates attributes for matching arrows 3. Updates attributes for matching
    *      nodes, including any endpoints of updated arrows that were in the original selection
    *
    * @return
    *   Updated ViewerGraphData with the new attributes applied
    */
  def updateAttributes(ids: ElementIds, selectionAttrs: AttributeUpdates): ViewerGraph =
    val classified = ids.classify

    val updatedArrows = arrows.view
      .filterKeys(classified.arrows)
      .mapValues(_.modify(_.attributes).using(selectionAttrs.applyTo))
      .toMap

    val updatedClusters = groups.view
      .filterKeys(classified.groups)
      .mapValues(_.modifyAttrs.using(attrs => AttributesOps.normalizeFill(selectionAttrs.applyTo(attrs))))
      .toMap

    val nodeIdsToUpdate = classified.nodes ++
      (updatedArrows.values.flatMap(_.endpoints).toSet & classified.nodes)

    val updatedNodes = nodeIdsToUpdate.foldLeft(nodes): (nodes, id) =>
      nodes.updated(
        id,
        nodes.getOrElse(id, nodeWithDefaults(id)).modifyAttrs.using(attrs => AttributesOps.normalizeFill(selectionAttrs.applyTo(attrs)))
      )

    modifyElements.using(
      _.copy(
        arrows = arrows ++ updatedArrows,
        nodes = updatedNodes,
        groups = groups ++ updatedClusters
      )
    )

  def getAttributesById(id: ElementId): Attributes =
    id match
      case id: ArrowId => arrows.get(id).fold(Attributes.empty)(_.attributes)
      case id: GroupId => groups.get(id).fold(Attributes.empty)(_.attributes)
      case id: NodeId  => nodes.get(id).fold(Attributes.empty)(_.attributes)

  /** Computes the attribute updates for a set of element IDs.
    *
    * This method aggregates the attributes from the provided element IDs, identifying conflicts where multiple elements have different
    * values for the same attribute. The resulting `AttributeUpdates` object can indicate whether an attribute has a single value or
    * multiple conflicting values.
    *
    * @param elementIds
    *   the set of element IDs for which to compute attribute updates
    * @return
    *   an `AttributeUpdates` instance encapsulating the aggregated attribute updates for the specified element IDs
    */
  def getAttributesUpdatesById[K <: ElementId](elementIds: ElementIds): AttributeUpdates =
    val mermaidStyleIndex = AttributesOps.MermaidStyleIndex.fromGraphAttributes(elements.graphAttributes)
    AttributeUpdates(
      elementIds.ids.foldLeft(Map.empty[AttributeId, AttrValueWithStatus]): (attrs, elementId) =>
        val baseAttrs = getAttributesById(elementId)
        val elemAttrs =
          AttributesOps.withMermaidEffectiveAttrs(elementId, baseAttrs, mermaidStyleIndex)
            .values
            .transform: (attrId, v) =>
              if (attrId in attrs) && !attrs(attrId).is(v) then Multiple else Single(v)

        attrs ++ elemAttrs
    )

  val defaultNodeTheme =
    Attributes.of(Sides -> 5, Shape -> Shape.box)

  val defaultEdgeTheme: Attributes =
    val dir = tpe match
      case GraphType.graph   => DirType.none
      case GraphType.digraph => DirType.both
    Attributes.of(
      Dir       -> dir,
      ArrowHead -> ArrowType.vee,
      ArrowTail -> ArrowType.none
    )

  def withDefaultTheme: ViewerGraph =
    this
      .modify(_.elements.defaultNodeAttributes).setTo(defaultNodeTheme)
      .modify(_.elements.defaultArrowAttributes).setTo(defaultEdgeTheme)

  /** Resets all attributes except 'label' from the specified elements.
    *
    * @return
    *   Updated ViewerGraph with attributes reset (keeping only 'label')
    */
  def resetAttributes(selection: ElementIds): ViewerGraph =

    val keepAttributes = Set(Label.attrId.value, Cluster.attrId.value)

    selection.ids.foldLeft(this): (currentGraph, elementId) =>
      val currentAttrs = currentGraph.getAttributesById(elementId).toDotAttr
      if currentAttrs.isEmpty then
        // No attributes to remove
        currentGraph
      else
        // Get keys as Strings from toDotAttr, compare with Label.attrId.value
        val keysToRemove = currentAttrs.map(_.id).toSet -- keepAttributes
        if keysToRemove.isEmpty then
          // Only label attribute was present (or attrs were empty), nothing to remove
          currentGraph
        else
          // Convert keys back to AttributeId for the update
          val updateForThisElement = AttributeUpdates.remove(keysToRemove.map(AttributeId(_)))
          // updateAttributes returns a *new* graph, so use it in the next fold step
          currentGraph.updateAttributes(ElementIds.from(elementId), updateForThisElement)

object AttributesOps:
  // Normalization helper available to both the trait and this companion
  private[graph] def normalizeFill(attrs: Attributes): Attributes =
    val fc = attrs.get(FillColor)
    fc match
      case Some(v) if v.toString == FillColor.none => attrs + (FillStyle.attrId -> AttrValue(false.toString))
      case Some(_)                                 => attrs + (FillStyle.attrId -> AttrValue(true.toString))
      case None                                    => attrs

  private val MermaidClassDefPrefix     = "mermaid_classDef_"
  private val MermaidClassDefTextPrefix = "mermaid_classDefText_"
  private val MermaidClassAttr          = AttributeId("mermaid_class")
  private val MermaidDefaultLinkStyleAttr = AttributeId("mermaid_linkStyle_default")
  private val MermaidEdgeStyleAttr        = AttributeId("mermaid_edgeStyle")

  private[graph] case class MermaidStyleIndex(
      classDefs:     Map[String, VectorMap[String, String]],
      classDefTexts: Map[String, VectorMap[String, String]],
      defaultEdgeStyles: VectorMap[String, String]
  )

  private object MermaidStyleIndex:
    val empty: MermaidStyleIndex = MermaidStyleIndex(Map.empty, Map.empty, VectorMap.empty)

    def fromGraphAttributes(graphAttrs: Attributes): MermaidStyleIndex =
      val classDefs = graphAttrs.values.collect { case (attrId, attrValue) if attrId.value.startsWith(MermaidClassDefPrefix) =>
        val className = attrId.value.stripPrefix(MermaidClassDefPrefix)
        className -> MermaidStyleDeclarations.parse(attrValue.toString)
      }

      val classDefTexts = graphAttrs.values.collect { case (attrId, attrValue) if attrId.value.startsWith(MermaidClassDefTextPrefix) =>
        val className = attrId.value.stripPrefix(MermaidClassDefTextPrefix)
        className -> MermaidStyleDeclarations.parse(attrValue.toString)
      }

      val defaultEdgeStyles = graphAttrs
        .get(MermaidDefaultLinkStyleAttr)
        .map(v => MermaidStyleDeclarations.parse(v.toString))
        .getOrElse(VectorMap.empty)

      MermaidStyleIndex(
        classDefs = classDefs,
        classDefTexts = classDefTexts,
        defaultEdgeStyles = defaultEdgeStyles
      )

  private[graph] def withMermaidEffectiveAttrs(
      elementId:         ElementId,
      attrs:             Attributes,
      mermaidStyleIndex: MermaidStyleIndex
  ): Attributes =
    elementId match
      case _: NodeId => withMermaidEffectiveNodeAttrs(attrs, mermaidStyleIndex)
      case _: ArrowId => withMermaidEffectiveEdgeAttrs(attrs, mermaidStyleIndex)
      case _: GroupId => withMermaidEffectiveGroupAttrs(attrs, mermaidStyleIndex)

  private def withMermaidEffectiveNodeAttrs(
      nodeAttrs:         Attributes,
      mermaidStyleIndex: MermaidStyleIndex
  ): Attributes =
    val (effectiveStyles, effectiveTextStyles) = resolveMermaidClassAndInlineStyles(nodeAttrs, mermaidStyleIndex)

    if effectiveStyles.isEmpty && effectiveTextStyles.isEmpty then nodeAttrs
    else

      val derived = scala.collection.mutable.ListBuffer.empty[(AttributeId, AttrValue)]
      effectiveStyles.get("fill").foreach(v => derived += FillColor.attrId -> AttrValue(v))
      effectiveStyles.get("stroke").foreach(v => derived += Color.attrId -> AttrValue(v))
      effectiveStyles.get("stroke-width").flatMap(parseCssNumber).foreach(v => derived += PenWidth.attrId -> AttrValue(v))
      effectiveStyles.get("font-family").foreach(v => derived += FontName.attrId -> AttrValue(v))
      effectiveStyles.get("font-size").flatMap(parseCssNumber).foreach(v => derived += FontSize.attrId -> AttrValue(v))
      effectiveStyles
        .get("color")
        .orElse(effectiveTextStyles.get("fill"))
        .foreach(v => derived += FontColor.attrId -> AttrValue(v))

      val derivedMissingOnly = derived.filterNot((attrId, _) => nodeAttrs.values.contains(attrId)).toMap
      nodeAttrs ++ derivedMissingOnly

  private def withMermaidEffectiveGroupAttrs(
      groupAttrs:        Attributes,
      mermaidStyleIndex: MermaidStyleIndex
  ): Attributes =
    val (effectiveStyles, effectiveTextStyles) = resolveMermaidClassAndInlineStyles(groupAttrs, mermaidStyleIndex)

    if effectiveStyles.isEmpty && effectiveTextStyles.isEmpty then groupAttrs
    else
      val derived = scala.collection.mutable.ListBuffer.empty[(AttributeId, AttrValue)]
      effectiveStyles.get("fill").foreach(v => derived += FillColor.attrId -> AttrValue(v))
      effectiveStyles.get("stroke").foreach(v => derived += PenColor.attrId -> AttrValue(v))
      effectiveStyles.get("stroke-width").flatMap(parseCssNumber).foreach(v => derived += PenWidth.attrId -> AttrValue(v))
      effectiveStyles.get("font-family").foreach(v => derived += FontName.attrId -> AttrValue(v))
      effectiveStyles.get("font-size").flatMap(parseCssNumber).foreach(v => derived += FontSize.attrId -> AttrValue(v))
      effectiveStyles
        .get("color")
        .orElse(effectiveTextStyles.get("fill"))
        .foreach(v => derived += FontColor.attrId -> AttrValue(v))

      val derivedMissingOnly = derived.filterNot((attrId, _) => groupAttrs.values.contains(attrId)).toMap
      groupAttrs ++ derivedMissingOnly

  private def withMermaidEffectiveEdgeAttrs(
      edgeAttrs:         Attributes,
      mermaidStyleIndex: MermaidStyleIndex
  ): Attributes =
    val perEdgeStyle = edgeAttrs
      .get(MermaidEdgeStyleAttr)
      .map(v => MermaidStyleDeclarations.parse(v.toString))
      .getOrElse(VectorMap.empty)

    val effectiveStyles =
      mermaidStyleIndex.defaultEdgeStyles ++ perEdgeStyle

    if effectiveStyles.isEmpty then edgeAttrs
    else
      val derived = scala.collection.mutable.ListBuffer.empty[(AttributeId, AttrValue)]
      effectiveStyles.get("stroke").foreach(v => derived += Color.attrId -> AttrValue(v))
      effectiveStyles.get("stroke-width").flatMap(parseCssNumber).foreach(v => derived += PenWidth.attrId -> AttrValue(v))
      effectiveStyles.get("color").foreach(v => derived += FontColor.attrId -> AttrValue(v))
      effectiveStyles.get("font-family").foreach(v => derived += FontName.attrId -> AttrValue(v))
      effectiveStyles.get("font-size").flatMap(parseCssNumber).foreach(v => derived += FontSize.attrId -> AttrValue(v))

      val derivedMissingOnly = derived.filterNot((attrId, _) => edgeAttrs.values.contains(attrId)).toMap
      edgeAttrs ++ derivedMissingOnly

  private def resolveMermaidClassAndInlineStyles(
      attrs:             Attributes,
      mermaidStyleIndex: MermaidStyleIndex
  ): (VectorMap[String, String], VectorMap[String, String]) =
    val classNames =
      attrs
        .get(MermaidClassAttr)
        .toList
        .flatMap(_.toString.split("\\s+"))
        .map(_.trim)
        .filter(_.nonEmpty)

    val defaultStyles      = mermaidStyleIndex.classDefs.getOrElse("default", VectorMap.empty)
    val defaultTextStyles  = mermaidStyleIndex.classDefTexts.getOrElse("default", VectorMap.empty)
    val classStyleLayers   = classNames.map(name => mermaidStyleIndex.classDefs.getOrElse(name, VectorMap.empty))
    val classTextLayers    = classNames.map(name => mermaidStyleIndex.classDefTexts.getOrElse(name, VectorMap.empty))
    val inlineStyleLayer   = MermaidStyleDeclarations.parse(attrs.get(Style.attrId).fold("")(_.toString))

    val effectiveStyles =
      (defaultStyles +: classStyleLayers :+ inlineStyleLayer)
        .foldLeft(VectorMap.empty[String, String])(_ ++ _)

    val effectiveTextStyles =
      (defaultTextStyles +: classTextLayers)
        .foldLeft(VectorMap.empty[String, String])(_ ++ _)

    (effectiveStyles, effectiveTextStyles)

  private val CssNumberWithOptionalUnit = raw"""([+-]?\d*\.?\d+)(?:[a-zA-Z%]+)?""".r

  private def parseCssNumber(rawValue: String): Option[String] =
    rawValue.trim match
      case CssNumberWithOptionalUnit(number) => Some(number)
      case _                                 => None
