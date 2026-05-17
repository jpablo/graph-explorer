package org.jpablo.graphexplorer.viewer.backends.mermaid

object MermaidClassAssignmentFallback:
  private val MermaidClassPrefix    = "class "
  private val MermaidClassDefPrefix = "classdef"

  /** Mermaid parser output can occasionally omit `class` assignments on nodes/subgraphs.
    * Merge missing assignments from raw source while keeping parser-provided classes authoritative when present.
    */
  def withSourceClassAssignments(
      sourceText: String,
      vertices:   Map[String, MermaidVertex],
      subgraphs:  List[MermaidSubgraph]
  ): (Map[String, MermaidVertex], List[MermaidSubgraph]) =
    val assignments = extractClassAssignmentsFromText(sourceText)

    val mergedVertices = vertices.map { case (rawKey, vertex) =>
      val sourceClasses = classesForVertex(rawKey, vertex.id, assignments)
      if sourceClasses.nonEmpty then
        val merged = mergeClasses(vertex.classes, sourceClasses)
        rawKey -> vertex.copy(classes = merged)
      else rawKey -> vertex
    }

    val mergedSubgraphs = subgraphs.map { subgraph =>
      assignments.get(subgraph.id) match
        case Some(fromSource) =>
          val merged = mergeClasses(subgraph.classes, fromSource)
          subgraph.copy(classes = merged)
        case _ => subgraph
    }

    (mergedVertices, mergedSubgraphs)

  /** Parses Mermaid `class` assignments from text.
    *
    * Supports declarations with multiple element IDs and class names:
    *   - class A pink
    *   - class A,B pink
    *   - class A,B pink,active
    */
  def extractClassAssignmentsFromText(sourceText: String): Map[String, List[String]] =
    sourceText.linesIterator
      .map(_.trim)
      .filter(line => line.nonEmpty && !line.startsWith("%%"))
      .filter { line =>
        val lower = line.toLowerCase
        lower.startsWith(MermaidClassPrefix) && !lower.startsWith(MermaidClassDefPrefix)
      }
      .flatMap { line =>
        val withoutPrefix = line.drop(MermaidClassPrefix.length).trim.stripSuffix(";")
        val normalized    = withoutPrefix.replaceAll("\\s*,\\s*", ",")
        normalized.split("\\s+", 2).toList match
          case targetPart :: classPart :: Nil =>
            val targets = splitCsv(targetPart)
            val classes = splitCsv(classPart)
            if targets.nonEmpty && classes.nonEmpty then
              targets.map(_ -> classes)
            else Nil
          case _ => Nil
      }
      .foldLeft(Map.empty[String, List[String]]) { case (acc, (targetId, classNames)) =>
        val merged = acc.getOrElse(targetId, Nil) ++ classNames
        acc + (targetId -> merged.distinct)
      }

  private def splitCsv(value: String): List[String] =
    value
      .split(',')
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)

  private def mergeClasses(parserClasses: List[String], sourceClasses: List[String]): List[String] =
    (parserClasses ++ sourceClasses).distinct

  private def classesForVertex(
      rawKey:      String,
      vertexId:    String,
      assignments: Map[String, List[String]]
  ): List[String] =
    val candidates = Vector(rawKey, vertexId).map(_.trim).filter(_.nonEmpty).distinct
    candidates.flatMap(id => assignments.getOrElse(id, Nil)).distinct.toList
