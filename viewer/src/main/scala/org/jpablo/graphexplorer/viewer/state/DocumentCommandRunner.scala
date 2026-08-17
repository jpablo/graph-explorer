package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.gxcore.command.{CommandResult, DocumentCommand, DocumentCommands}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph

/** The UI's door into the command vocabulary (D7.1).
  *
  * D7.1 is blunt about why this exists: *"The UI must not keep calling `Ops`
  * functions directly while RPC goes through a parallel path."* Two paths to the
  * same operation are two implementations, and they drift — the menu item and
  * the socket request stop agreeing about what "ungroup" means. Undo/redo, the
  * audit log and replay all fall out of a named, serializable command set, and
  * none of them are cheap to retrofit onto closures.
  *
  * What stays in the UI is what is genuinely about the VIEW: which elements to
  * select after a record is split, what to tell the user when a command is
  * refused. The command produces the new graph; the UI decides what to look at.
  * That line is the same one D7.2 draws between the document and session tiers,
  * and keeping it here is what lets the identical command run headless in `gx`.
  */
trait DocumentCommandRunner:
  this: ViewerState =>

  /** Run a document command against the current graph.
    *
    * `onApplied` receives the graph before and after, which is how the UI does
    * its view-level follow-up — selecting the record node that `combine` just
    * created, for instance — without that follow-up leaking into the command.
    *
    * A refusal reaches the user rather than vanishing. The UI mostly cannot
    * produce one (a menu item is greyed out when it does not apply), but
    * "mostly" is the interesting part: a selection can go stale between the
    * click and the update, and silently doing nothing is how that becomes a bug
    * report about the app "ignoring" a keystroke.
    */
  def runDocumentCommand(
      command:   DocumentCommand,
      onApplied: (before: ViewerGraph, after: ViewerGraph) => Unit = (_, _) => ()
  ): Unit =
    phases.fullGraphV.update: graph =>
      DocumentCommands.run(graph, command) match
        case Right(CommandResult.Updated(next)) =>
          onApplied(graph, next)
          next

        case Right(CommandResult.Answered(_)) =>
          // A query changes nothing. The UI has no caller for one yet — it reads
          // the graph directly — but the vocabulary is one set, so running a
          // query here has to be harmless rather than an error.
          graph

        case Left(error) =>
          errorBus.emit(error.message)
          graph
