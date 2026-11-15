package org.jpablo.graphexplorer.gxcore.model

/** Everything about a diagram that is not its text.
  *
  * The metadata/text split is a design rule, not bookkeeping (§5.3.1). Anything
  * living here survives a pull, so folding a cluster or hiding a node never
  * conflicts with a regenerating origin; anything living in the text does
  * conflict. New features should be asked which side they fall on, because the
  * answer decides whether they break the follow-the-file flow.
  *
  * The metadata has no name and no folder (CONTEXT.md, ADR 0001). The name of
  * a diagram is the title in its source, or else its file name. The library
  * has no folders. A person uses tags and the folders of the file system. The
  * web app keeps a stored name for each browser diagram, outside this type
  * (`DiagramSnapshot.name` in the viewer).
  */
final case class DiagramMetadata(
    hiddenElements:  Set[String] = Set.empty,
    collapsedGroups: Set[String] = Set.empty,
    tags:            List[String] = Nil,
    notes:           String = "",
    /** This flag controls whether the viewer detects the language of the source
      * again after each edit.
      *
      * The flag is here and not in the text because of the question in §5.3.1:
      * the flag survives a pull. A pull from an origin that a tool generated
      * again must not change the language mode that the user selected. `None`
      * is the value in each diagram from before the mode existed. The viewer
      * reads it as "on".
      */
    autoDetectFormat: Option[Boolean] = None,
    /** The renderer that the person selected for this diagram: `graphviz` or
      * `mermaid` (ADR 0002). `None` is the renderer of the language of the
      * source.
      *
      * The choice is here and not in the text for the reason of the flag
      * above: it survives when a tool writes the file again. A reader must
      * accept a value that it does not know, and then use `None`, because a
      * newer build can have a renderer that this build does not have.
      */
    renderer: Option[String] = None,
    /** The extractor that the person selected for this diagram: `dot` or
      * `mermaid` (ADR 0002). `None`: the app finds the extractor from the
      * content of the data source, or else from the name of the file.
      *
      * As for the renderer, a reader must accept a value that it does not
      * know, and then use `None`.
      *
      * `autoDetectFormat` stays, because each stored diagram has it. A
      * selection here always comes with `autoDetectFormat = Some(false)`.
      */
    extractor: Option[String] = None,
    /** True when the person told Graph Explorer not to write to the data
      * source of this diagram (CONTEXT.md, "Locked").
      *
      * The app and `gx` then refuse each change of the source. A change of
      * the metadata stays possible, because the metadata is not in the data
      * source. The app still follows a change that a different tool makes.
      */
    locked: Boolean = false
) derives CanEqual

object DiagramMetadata:

  /** The metadata of a diagram that has none yet. */
  val empty: DiagramMetadata = DiagramMetadata()
