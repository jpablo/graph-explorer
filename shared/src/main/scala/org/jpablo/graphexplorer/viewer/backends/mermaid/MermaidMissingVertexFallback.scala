package org.jpablo.graphexplorer.viewer.backends.mermaid

object MermaidMissingVertexFallback:

  /** Mermaid parser output can omit vertices entirely while still exposing edges/subgraphs.
    * Reconstruct missing vertices from source labels/classes and referenced ids.
    */
  def withSourceVertices(
      sourceText: String,
      vertices:   Map[String, MermaidVertex],
      edges:      List[MermaidEdge],
      subgraphs:  List[MermaidSubgraph]
  ): Map[String, MermaidVertex] =
    val labelsById = MermaidVertexLabelFallback.extractVertexLabelsFromText(sourceText)
    val classesById = MermaidClassAssignmentFallback.extractClassAssignmentsFromText(sourceText)

    val referencedIds: Set[String] =
      edges.iterator.flatMap(e => Iterator(e.start, e.end)).toSet ++
        subgraphs.iterator.flatMap(_.nodes).toSet ++
        labelsById.keySet ++
        classesById.keySet

    def representedIds(rawKey: String, vertex: MermaidVertex): Set[String] =
      val vertexId = Option(vertex.id).filter(_.nonEmpty).toSet
      (Set(rawKey) ++ vertexId).intersect(referencedIds)

    val coveredIds = vertices.iterator.flatMap { case (rawKey, vertex) => representedIds(rawKey, vertex) }.toSet
    val missingIds = referencedIds -- coveredIds

    if missingIds.isEmpty then vertices
    else
      val synthesized = missingIds.toVector.sorted.map { id =>
        val label   = labelsById.getOrElse(id, id)
        val classes = classesById.getOrElse(id, Nil)
        id -> MermaidVertex(id = id, text = label, classes = classes)
      }
      vertices ++ synthesized
