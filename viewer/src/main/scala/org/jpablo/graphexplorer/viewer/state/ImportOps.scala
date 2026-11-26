package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, PastedDiagram}
import org.jpablo.graphexplorer.viewer.backends.mermaid.isMermaidStateDiagram
import org.jpablo.graphexplorer.viewer.graph.{InsertItem, NodeInsertion}
import org.jpablo.graphexplorer.viewer.models.GroupId

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Failure, Success}

/** Bringing a whole document IN — the mirror of [[ExportOps]], which only ever
  * sends one out.
  */
trait ImportOps:
  this: ViewerState =>

  /** Replace the entire document with `text`, read as `format`.
    *
    * Text and language land together — see `InternalPhases.replaceDocument` for
    * why they cannot be two writes: in between them the outgoing document is
    * observable under the incoming backend, which every consumer then fails on.
    */
  def replaceSource(text: String, format: DiagramFormat): Unit =
    val languageChanges = format != formatSelection.now()
    phases.replaceDocument(text, format)
    if languageChanges then selectExtractorOf(format)

  /** Make the selected extractor follow a new language of the whole document.
    *
    * The paste, the push of text, and the undo of a whole document come here.
    * The restore of a diagram takes the language from the selected extractor
    * first. If the extractor stays on the old language, the next view parses
    * the new text in the old language, and the parse fails.
    *
    * The person selected a language by hand, and the new text is the newer
    * instruction (see `setDiagramFormat`). Thus the selection stays, but it
    * changes to the new language. Auto-detect stays off. With no selection,
    * this writes nothing.
    *
    * This writes the metadata after `replaceDocument`. Thus the view already
    * has the new language when the extractor changes, and the observer of the
    * extractor does nothing.
    */
  private def selectExtractorOf(format: DiagramFormat): Unit =
    val current  = metadata.now()
    val selected = current.extractor.isDefined || !current.autoDetectFormat.getOrElse(true)
    val id       = languages.extractor(format).id
    if selected && !current.extractor.contains(id) then metadata.metadata.update(_.copy(extractor = Some(id)))

  /** As [[replaceSource]], with the language read off the text itself. */
  def replaceSourceDetectingFormat(text: String): DiagramFormat =
    val format = DiagramFormat.detect(text)
    replaceSource(text, format)
    format

  /** A whole document restored from the editor's own history (undo/redo).
    *
    * The selector has to travel with it, or undoing a paste hands the old DOT
    * back to the Mermaid parser and the recovery reads as a syntax error. Only a
    * DECLARED format moves it though — an undo must not second-guess a language
    * the user set by hand over text that declares nothing.
    */
  def restoreSource(text: String): Unit =
    DiagramFormat.declared(text) match
      // Undeclared: the language is staying put, so this is an ordinary text
      // change and there is no pair to keep consistent.
      case None         => sourceText.set(text)
      case Some(format) => replaceSource(text, format)

  /** Replace the diagram with the DOT or Mermaid source on the system clipboard.
    *
    * Recoverable rather than guarded by a confirmation: the swap goes through
    * `sourceText`, so it lands in CodeMirror's history and Undo puts the old
    * document back. Nothing is touched when the clipboard holds no text — an
    * empty clipboard must not be a way to erase the diagram.
    */
  def pasteDiagram(): Unit =
    readText().onComplete:
      case Success(raw) =>
        PastedDiagram.from(raw) match
          case Some(pasted) => applyPaste(pasted)
          case None         => infoBus.emit("Nothing to paste: the clipboard holds no text")
      case Failure(err) =>
        errorBus.emit(s"Could not read the clipboard: ${err.getMessage}")

  /** The same replacement, driven by the user's own paste GESTURE (⌘V on the
    * canvas), which hands the text over with the event.
    *
    * Stricter than [[pasteDiagram]], deliberately: it acts only on text that
    * DECLARES a language. The explicit command is a request, so `detect`'s DOT
    * fallback is the right answer there even for a bare fragment. ⌘V is reflex,
    * and this very app puts things on the clipboard that are not diagrams —
    * "Copy selection as SVG" sits on `c`, one key away — which the fallback
    * would accept as DOT and then fail to parse, over the user's work.
    */
  def pasteDiagramFromGesture(raw: String): Unit =
    PastedDiagram.from(raw).filter(_.declared) match
      case Some(pasted) => applyPaste(pasted)
      case None         => infoBus.emit("The clipboard holds no DOT or Mermaid diagram")

  /** The paste gesture (⌘V on the canvas) with the text of the clipboard.
    *
    *   - Text that declares a DOT or a Mermaid diagram replaces the diagram ([[pasteDiagramFromGesture]]).
    *   - Other text becomes a new node, with the text as its label ([[insertItems]]).
    *   - Blank text, and SVG markup, change nothing. "Copy selection as SVG" is one key away from ⌘V, and its markup is
    *     not a label.
    */
  def pasteTextFromGesture(raw: String): Unit =
    if PastedDiagram.from(raw).exists(_.declared) || raw.trim.isEmpty || ImportOps.isSvgMarkup(raw) then
      pasteDiagramFromGesture(raw)
    else insertItems(Seq(InsertItem.Text(raw)), "paste the text")

  /** The paste gesture with an image on the clipboard. `read` gives the image item. It reads the file, so it is
    * asynchronous. The refusal comes first, so that a locked diagram does not wait for the read.
    */
  def pasteImageFromGesture(read: => Future[InsertItem]): Unit =
    insertFromGesture("paste the image", image = true, read.map(Seq(_)))

  /** The drop gesture of files on the canvas. `read` gives one item for each file (see `TransferItems.readItems`).
    * `images` tells if a file is an image, before the read. All the files become nodes in one change, so Undo removes
    * them in one step.
    */
  def dropFilesFromGesture(images: Boolean, read: => Future[Seq[InsertItem]]): Unit =
    insertFromGesture("drop the files", images, read)

  /** The drop gesture of links (from a browser): a node for each link, with the link as its label and its URL. A
    * command-click on the node opens the link.
    */
  def dropLinksFromGesture(links: Seq[String]): Unit =
    insertItems(links.map(link => InsertItem.File(link, Some(link))), "drop the link")

  /** The drop gesture of text: a new node with the text as its label.
    *
    * Unlike a paste, a drop never replaces the diagram, also for text that declares a diagram: a dropped `.dot` file
    * also only gives a node. Blank text, and SVG markup, change nothing.
    */
  /** The drop gesture of files from the desktop: Tauri gives the paths of the files (see `DesktopDrop`). An image
    * file becomes an image node, and each other file becomes a node with a link to the file. A command-click on the
    * node opens the file.
    */
  def dropPathsFromGesture(paths: Seq[String]): Unit =
    if paths.nonEmpty then
      val images = paths.exists(p => org.jpablo.graphexplorer.viewer.desktop.DesktopFiles.imageTypeOf(p).isDefined)
      insertFromGesture("drop the files", images, readDroppedPaths(paths))

  def dropTextFromGesture(raw: String): Unit =
    if raw.trim.isEmpty || ImportOps.isSvgMarkup(raw) then infoBus.emit("Nothing to drop: the drag holds no text")
    else insertItems(Seq(InsertItem.Text(raw)), "drop the text")

  /** Refuse now, or read the items and add them. `action` names the gesture in the message, for example "drop the
    * files".
    */
  private def insertFromGesture(action: String, image: Boolean, read: => Future[Seq[InsertItem]]): Unit =
    insertRefusal(action, image) match
      case Some(message) => errorBus.emit(message)
      case None =>
        // A diagram with a file keeps each image as a file next to it (see `LocalFileOps.storeImages`).
        read.flatMap(storeImages).onComplete:
          case Success(items) => insertItems(items, action)
          case Failure(error) => errorBus.emit(s"The viewer did not $action: ${error.getMessage}")

  /** Add a new node for each item, as the New node command adds a node: with no arrow, and in the selected group when
    * a group is selected. The layout gives the place of each node (see `NodeInsertion`). The new nodes become the
    * selection. A selected box is its collapsed group: the nodes go into the group, and the box stays selected, as for
    * New node.
    *
    * The edit is one canvas edit, so the printer of the language of the diagram writes the nodes, and Undo removes all
    * of them in one step. A paste and a drop use this one function.
    */
  def insertItems(items: Seq[InsertItem], action: String): Unit =
    val image = items.exists:
      case _: InsertItem.Image => true
      case _                   => false
    if items.nonEmpty then
      insertRefusal(action, image) match
        case Some(message) => errorBus.emit(message)
        case None =>
          // The setter of `fullGraphV` can still refuse the edit (a file would lose content). Then the selection stays.
          val applies = phases.graphEditApplies
          val target  = selection.targets().ids.headOption
          val group   = target.collect { case g: GroupId => g }
          phases.fullGraphV.update: graph =>
            val (next, ids) = NodeInsertion.insertAll(graph, items, group)
            // A node in a collapsed group has no shape on the canvas. Then its box stays selected.
            if applies && !target.exists(hasBox) then selection.set1(ids.toSet)
            next

  /** Why the gesture `action` (for example "paste the image") cannot add a node now, or `None` when it can. */
  private def insertRefusal(action: String, image: Boolean): Option[String] =
    if metadata.now().locked then
      Some(s"The viewer did not $action, because the diagram is locked. Unlock the diagram to change it.")
    else
      loadStatus.now() match
        case DiagramLoadStatus.RenderOnly(_) =>
          Some(s"The viewer did not $action, because the diagram is view-only. Change its text instead.")
        case DiagramLoadStatus.Failed(_) =>
          Some(s"The viewer did not $action, because the text of the diagram has an error. Correct the text first.")
        case _ if image && isMermaidStateDiagram(fullGraphNow()) =>
          Some(s"The viewer did not $action, because a Mermaid state diagram cannot show an image.")
        case _ => None

  private def applyPaste(pasted: PastedDiagram): Unit =
    if metadata.now().locked then
      errorBus.emit("The viewer did not paste the diagram, because the diagram is locked. Unlock the diagram to change it.")
    else
      replaceSource(pasted.source, pasted.format)
      infoBus.emit(s"Pasted ${pasted.format.displayName} diagram from the clipboard")

object ImportOps:

  /** True when `text` is SVG markup, for example the text of "Copy selection as SVG". */
  def isSvgMarkup(text: String): Boolean =
    val t = text.trim.toLowerCase
    t.startsWith("<svg") || (t.startsWith("<?xml") && t.contains("<svg"))
