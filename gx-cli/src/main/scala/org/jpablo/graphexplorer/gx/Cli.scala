package org.jpablo.graphexplorer.gx

import org.jpablo.graphexplorer.gxcore.fs.*
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.gxcore.command.{
  AnyCommand,
  CommandError,
  CommandResult,
  DiagramText,
  DocumentCommand,
  DocumentCommands,
  RecordCommand,
  SessionCodec,
  SessionCommand,
  RecordCommands,
  RecordResult
}
import org.jpablo.graphexplorer.gxcore.rpc.ChannelError

import java.nio.file.{Path, Paths}
import scala.util.control.NonFatal

/** Why a reference resolved to no single diagram. Two cases, not one: "you
  * typed a name nothing has" and "you typed a name several things share" need
  * different answers from the user.
  */
private enum RefError derives CanEqual:
  case NotFound(ref: String)
  case Ambiguous(ref: String, matches: Vector[Diagram])

/** What one reconciliation did.
  *
  * `failure` is a store write that did NOT land. It used to be discarded —
  * `env.store.save(updated)` with the Either thrown away at three sites — so
  * `gx sync` printed Behind/Ahead and exited 0 after failing to persist the
  * record it had just reconciled. The next run then redid the same work from
  * the same stale baseline, silently, forever.
  */
private case class SyncOutcome(diagram: Diagram, state: SyncState, failure: Option[String])

/** What `gx open` decided a reference means (§3.1 of
  * docs/desktop-open-targets-and-persistence.md).
  *
  * Distinct from [[Target]], which answers "library record or file on disk?"
  * for the document tier. This one crosses the wire: the desktop has to know
  * which it was given, because a record and a loose file have different owners
  * and different persistence rules, and a path cannot tell them apart. Sending
  * only a path is what made an open lose its record identity — and made an
  * UNBOUND record unopenable, since it has no path to send.
  */
private enum OpenTarget derives CanEqual:
  case Library(id: DiagramId)
  case File(path: Path)

/** What a reference on the command line turned out to mean. */
private enum Target derives CanEqual:
  case InLibrary(diagram: Diagram)

  /** A path that is not in the library. Still perfectly usable: under D1 a
    * document's revision is the hash of its bytes, so reading and conditionally
    * writing a file needs no registration first. v1 could not do this — `get`
    * failed with "path is not currently watched" until you had called `watch`.
    */
  case OnDisk(path: Path)

/** The `gx` command surface (§8).
  *
  * Every command here is D7.2's **document** or **record** tier, so every one of
  * them works with no desktop running — which is the whole point of the
  * redesign. `open` is the single session-tier command, and the only one that
  * can fail for want of a window.
  */
object Cli:

  private val Usage =
    """gx — Graph Explorer command line
      |
      |  gx status [--json]                     library and desktop state
      |  gx import <path> [--mode M] [--folder F] [--name N]
      |  gx ls [--folder F] [--json]
      |  gx get <ref> [--json]                  print a diagram's text
      |  gx set <ref> (--stdin | --text T) [--base H]
      |  gx bind <ref> <path> [--mode M]        attach an origin
      |  gx unbind <ref>
      |  gx sync [<ref>] [--all] [--json]       reconcile with origins
      |  gx watch [<ref>...] [--all] [--json]   stream changes to stdout
      |  gx run <ref> <command> [--params J]    a document or record command
      |  gx session <command> [--params J]      act on the LIVE view (needs a desktop)
      |  gx open <ref> [--loose]                show it in the desktop
      |                                         (--loose: the FILE at that path,
      |                                          never the record bound to it)
      |
      |  gx skill [<version>] [--latest]        where the agent skill lives
      |
      |  M = detached | pull | push | sync      (default: pull)
      |  gx run --list                          the commands `run` accepts
      |
      |Everything except `open` works with no desktop running.
      |""".stripMargin

  def run(argv: Vector[String], env: CliEnv): Int =
    argv.headOption match
      case None | Some("--help") | Some("-h") | Some("help") =>
        env.out(Usage)
        if argv.isEmpty then ExitCode.Usage else ExitCode.Ok

      case Some("--version") | Some("version") =>
        env.out(buildinfo.BuildInfo.version)
        ExitCode.Ok

      case Some(command) =>
        Args.parse(argv.drop(1)) match
          case Left(why) =>
            env.err(s"gx: $why")
            ExitCode.Usage
          case Right(args) =>
            try dispatch(command, args, env)
            catch
              case NonFatal(e) =>
                env.err(s"gx: unexpected failure: $e")
                ExitCode.Unknown

  private def dispatch(command: String, args: Args, env: CliEnv): Int =
    command match
      case "status" => status(args, env)
      case "import" => importFile(args, env)
      case "ls"     => ls(args, env)
      case "get"    => get(args, env)
      case "set"    => set(args, env)
      case "bind"   => bind(args, env)
      case "unbind" => unbind(args, env)
      case "sync"   => sync(args, env)
      case "watch"  => watch(args, env)
      case "run"     => runCommand(args, env)
      case "session" => sessionCommand(args, env)
      case "open"   => open(args, env)
      case "skill"  => skill(args, env)
      case other =>
        env.err(s"gx: unknown command '$other'\n")
        env.err(Usage)
        ExitCode.Usage

  // ------------------------------------------------------------- status

  /** Reports and exits 0 even with no desktop.
    *
    * In v1 this was the ONLY command that worked without one, and it exited 2 to
    * say so. Now a missing desktop is ordinary — it stops exactly one command —
    * so reporting it is information, not failure.
    */
  private def status(args: Args, env: CliEnv): Int =
    val diagrams = env.store.list()
    val bound    = diagrams.count(_.isBound)

    // One question, not two. v1 asked "is it running?" and then asked the
    // desktop separately; a single `status` call answers both, and answers the
    // first one the only way that means anything — by connecting.
    val desktop = env.rpc("status", ujson.Obj()).toOption.flatMap(_.objOpt)
    val watches = desktop.flatMap(_.get("watches")).flatMap(_.arrOpt).map(_.size).getOrElse(0)

    // Three states, not two. The socket now answers from the moment the process
    // starts — it is bound before the webview, which on Windows can take half a
    // minute — so "the call succeeded" no longer means "there is a window".
    // A desktop that is starting used to be reported as absent, which is the
    // one thing it definitely was not.
    //
    // `state` absent means a desktop from before this existed: it only ever
    // answered once it was fully up, so treating it as running is right.
    val starting = desktop.flatMap(_.get("state")).flatMap(_.strOpt).contains("starting")
    val running  = desktop.isDefined && !starting

    if args.json then
      env.out(
        ujson.Obj(
          "ok"              -> true,
          "library"         -> env.store.root.toString,
          "diagrams"        -> diagrams.size,
          "bound"           -> bound,
          "desktopRunning"  -> running,
          "desktopStarting" -> starting,
          "desktopWatches"  -> watches
        ).render(indent = 2)
      )
    else
      env.out(s"library:  ${env.store.root}")
      env.out(s"diagrams: ${diagrams.size} ($bound bound)")
      // NOT "open". This counts the desktop's WATCH registry, which only
      // `open_document` (the UI) and the `watch`/`show` RPC ever add to —
      // `gx import` touches neither. Saying "1 open" right after an import
      // told the reader the desktop had their diagram when it did not, which
      // is the one thing the number cannot mean.
      if running then env.out(s"desktop:  running (${watching(watches)})")
      else if starting then env.out("desktop:  starting (its window is not up yet)")
      else env.out("desktop:  not running (only `gx open` needs it)")
    ExitCode.Ok

  /** What the desktop is following on disk, phrased so it cannot be misread as
    * what the desktop is displaying. Those are different sets: a watched file
    * need not be on screen, and D7.3 means an imported diagram is on neither.
    */
  private def watching(n: Int): String =
    if n == 0 then "watching nothing"
    else if n == 1 then "watching 1 file"
    else s"watching $n files"

  // ------------------------------------------------------------- import

  private def importFile(args: Args, env: CliEnv): Int =
    args.positionalAt(0) match
      case None =>
        env.err("gx: import needs a path")
        ExitCode.Usage
      case Some(raw) =>
        modeOf(args, default = SyncMode.Pull) match
          case Left(why) => env.err(s"gx: $why"); ExitCode.Usage
          case Right(mode) =>
            val path = env.cwd.resolve(raw)
            checkPolicy(path, env) match
              case Left(code) => code
              case Right(resolved) =>
                Documents.read(resolved) match
                  case Left(err) =>
                    env.err(s"gx: cannot read ${resolved}: ${describe(err)}")
                    ExitCode.InvalidPathOrPolicy
                  case Right(doc) =>
                    val origin = FileOrigins.originOf(resolved, env.cwd)
                    val scheme = origin.scheme
                    scheme.rejectionFor(mode) match
                      case Some(why) =>
                        env.err(s"gx: $why")
                        ExitCode.Usage
                      case None =>
                        val existing = env.store.findByOrigin(origin)
                        if existing.nonEmpty then
                          // §3.1: uniqueness on origin is a policy, not a
                          // structural rule. Warn and reuse rather than
                          // silently creating a second record that will fight
                          // the first over the same file.
                          env.err(s"gx: already imported as ${existing.head.id.value}")
                          printDiagram(existing.head, args, env)
                          ExitCode.Ok
                        else
                          val name = args.value("name").getOrElse(defaultName(resolved))
                          val d = Diagram(
                            id = DiagramId(freshId(name, env)),
                            name = name,
                            folder = args.value("folder").map(FolderPath.parse).getOrElse(FolderPath.root),
                            format = detectFormat(doc.text),
                            text = doc.text,
                            binding = Some(Binding(origin, mode, doc.hash, env.now())),
                            metadata = DiagramMetadata.empty,
                            createdAt = env.now(),
                            updatedAt = env.now()
                          )
                          persist(d, env): saved =>
                            env.audit.record(AuditEvent.Allowed(resolved.toString, "import"))
                            printDiagram(saved, args, env)
                            ExitCode.Ok

  // ----------------------------------------------------------------- ls

  private def ls(args: Args, env: CliEnv): Int =
    val all = env.store.list()
    val selected = args.value("folder").map(FolderPath.parse) match
      case Some(f) => all.filter(_.folder.isUnder(f))
      case None    => all
    if args.json then
      env.out(ujson.Arr.from(selected.map(summaryJson)).render(indent = 2))
    else if selected.isEmpty then env.out("(no diagrams)")
    else
      for d <- selected do
        val origin = d.binding.map(b => s"  ${b.mode}  ${b.origin.value}").getOrElse("")
        env.out(f"${d.id.value}%-28s ${d.folder.render}%-16s ${d.name}$origin")
    ExitCode.Ok

  // ---------------------------------------------------------------- get

  private def get(args: Args, env: CliEnv): Int =
    withTarget(args, env): target =>
      target match
        case Target.InLibrary(d) =>
          if args.json then env.out(summaryJson(d).render(indent = 2)) else env.out(d.text)
          ExitCode.Ok
        case Target.OnDisk(path) =>
          Documents.read(path) match
            case Left(err) =>
              env.err(s"gx: ${describe(err)}")
              ExitCode.InvalidPathOrPolicy
            case Right(doc) =>
              if args.json then
                env.out(
                  ujson
                    .Obj("path" -> path.toString, "hash" -> doc.hash.hex, "text" -> doc.text)
                    .render(indent = 2)
                )
              else env.out(doc.text)
              ExitCode.Ok

  // ---------------------------------------------------------------- set

  private def set(args: Args, env: CliEnv): Int =
    textFrom(args, env) match
      case Left(why) => env.err(s"gx: $why"); ExitCode.Usage
      case Right(text) =>
        withTarget(args, env)(applyText(_, text, args, env, source = "cli"))

  /** Write new text to whatever the reference turned out to mean.
    *
    * Shared by `set` and `run` rather than copied: a command that rewrites the
    * diagram has to land the same way a hand-written `set` does — same
    * compare-and-swap, same mode rules, same audit line — or "the same edit"
    * would mean two different things depending on how it was expressed.
    */
  private def applyText(target: Target, text: String, args: Args, env: CliEnv, source: String): Int =
    target match
      case Target.OnDisk(path) => writeFile(path, text, args, env, source)
      case Target.InLibrary(d) =>
        val updated = d.copy(text = text, updatedAt = env.now())
        d.binding.filter(b => b.mode.pushes) match
          case None =>
            // Local-only by mode: Pull keeps UI/CLI edits in the store and
            // never writes them back (§5.3). Saving the record is the whole
            // operation, and the divergence it creates is reported by
            // `gx sync` rather than resolved behind the user's back.
            persist(updated, env): _ =>
              reportSet(updated, wroteOrigin = false, args, env); ExitCode.Ok
          case Some(binding) =>
            binding.origin.filePath.map(Paths.get(_)) match
              case None =>
                env.err(s"gx: cannot write to ${binding.origin.value}")
                ExitCode.InvalidPathOrPolicy
              case Some(path) =>
                Documents.write(path, text, binding.baseHash) match
                  case Left(DocumentError.Conflict(_, expected, actual)) =>
                    conflict(path.toString, expected, actual, env)
                  case Left(err) =>
                    env.err(s"gx: ${describe(err)}")
                    ExitCode.InvalidPathOrPolicy
                  case Right(doc) =>
                    val synced = updated.copy(
                      text = doc.text,
                      binding = Some(binding.copy(baseHash = doc.hash, lastSyncAt = env.now()))
                    )
                    persist(synced, env): _ =>
                      env.audit.record(AuditEvent.Written(path.toString, doc.hash, source))
                      reportSet(synced, wroteOrigin = true, args, env)
                      ExitCode.Ok

  private def writeFile(path: Path, text: String, args: Args, env: CliEnv, source: String): Int =
    checkPolicy(path, env) match
      case Left(code) => code
      case Right(resolved) =>
        val base = args.value("base").map(ContentHash.fromHex).orElse(Documents.hashOf(resolved))
        base match
          case None =>
            // No file yet: creating one is not a conditional write, and pretending
            // it is would make `gx set` unable to make a new diagram.
            Documents.create(resolved, text) match
              case Left(err) => env.err(s"gx: ${describe(err)}"); ExitCode.InvalidPathOrPolicy
              case Right(doc) =>
                env.audit.record(AuditEvent.Written(resolved.toString, doc.hash, source))
                if args.json then env.out(fileJson(resolved, doc.hash).render(indent = 2))
                else env.out(s"${resolved}  ${doc.hash.hex.take(12)}")
                ExitCode.Ok
          case Some(expected) =>
            Documents.write(resolved, text, expected) match
              case Left(DocumentError.Conflict(_, exp, actual)) => conflict(resolved.toString, exp, actual, env)
              case Left(err) => env.err(s"gx: ${describe(err)}"); ExitCode.InvalidPathOrPolicy
              case Right(doc) =>
                env.audit.record(AuditEvent.Written(resolved.toString, doc.hash, source))
                if args.json then env.out(fileJson(resolved, doc.hash).render(indent = 2))
                else env.out(s"${resolved}  ${doc.hash.hex.take(12)}")
                ExitCode.Ok

  // --------------------------------------------------------- bind/unbind

  private def bind(args: Args, env: CliEnv): Int =
    (args.positionalAt(0), args.positionalAt(1)) match
      case (Some(ref), Some(rawPath)) =>
        modeOf(args, default = SyncMode.Pull) match
          case Left(why) => env.err(s"gx: $why"); ExitCode.Usage
          case Right(mode) =>
            resolveRef(ref, env) match
              case Left(err) => reportRef(err, env)
              case Right(d) =>
                val path = env.cwd.resolve(rawPath)
                checkPolicy(path, env) match
                  case Left(code) => code
                  case Right(resolved) =>
                    val origin = FileOrigins.originOf(resolved, env.cwd)
                    origin.scheme.rejectionFor(mode) match
                      case Some(why) => env.err(s"gx: $why"); ExitCode.Usage
                      case None =>
                        // Bind against what is on disk now, so the binding starts
                        // agreed rather than immediately Behind. A missing file
                        // binds against the diagram's own text: the origin is
                        // where it is going, not where it came from.
                        val base = Documents
                          .hashOf(resolved)
                          .getOrElse(Hashing.ofText(d.text, LineEnding.Lf))
                        val bound = d.copy(
                          binding = Some(Binding(origin, mode, base, env.now())),
                          updatedAt = env.now()
                        )
                        persist(bound, env): _ =>
                          printDiagram(bound, args, env); ExitCode.Ok
      case _ =>
        env.err("gx: bind needs a diagram and a path")
        ExitCode.Usage

  private def unbind(args: Args, env: CliEnv): Int =
    args.positionalAt(0) match
      case None =>
        env.err("gx: unbind needs a diagram")
        ExitCode.InvalidPathOrPolicy
      case Some(ref) =>
        resolveRef(ref, env) match
          case Left(err) => reportRef(err, env)
          case Right(d) =>
            val detached = d.copy(binding = None, updatedAt = env.now())
            persist(detached, env): _ =>
              printDiagram(detached, args, env); ExitCode.Ok

  // --------------------------------------------------------------- sync

  private def sync(args: Args, env: CliEnv): Int =
    val (unknown, found) = selectedRefs(args, env)
    // Report every bad ref, not just the first: a script passing ten names
    // wants all the typos at once.
    if unknown.nonEmpty then unknown.map(reportRef(_, env)).last
    else syncTargets(found.filter(_.isBound), args, env)

  private def syncTargets(targets: Vector[Diagram], args: Args, env: CliEnv): Int =
    if targets.isEmpty then
      env.out(if args.json then "[]" else "(nothing bound to sync)")
      ExitCode.Ok
    else
      val results  = targets.map(syncOne(_, env))
      val diverged = results.count(_.state == SyncState.Diverged)
      val failures = results.flatMap(o => o.failure.map(o.diagram.id.value -> _))
      if args.json then
        env.out(
          ujson.Arr
            .from(results.map(o =>
              ujson.Obj("id" -> o.diagram.id.value, "state" -> o.state.toString)
            ))
            .render(indent = 2)
        )
      else for o <- results do env.out(f"${o.diagram.id.value}%-28s ${o.state}")

      for (id, why) <- failures do env.err(s"gx: $id reconciled but could not be saved: $why")

      // A write that did not land outranks divergence: divergence is a state
      // the user can act on, an unsaved record is one they have not been told
      // about. Divergence is a state, not an error (§5.2) — but a script that
      // just pushed and wants to know whether it landed deserves a non-zero code.
      if failures.nonEmpty then ExitCode.Unknown
      else if diverged > 0 then ExitCode.Conflict
      else ExitCode.Ok

  private def syncOne(d: Diagram, env: CliEnv): SyncOutcome =
    // Save, and KEEP the answer. Every early return below goes through here.
    def store(updated: Diagram, state: SyncState): SyncOutcome =
      env.store.save(updated) match
        case Left(e)      => SyncOutcome(updated, state, Some(e.toString))
        case Right(saved) => SyncOutcome(saved, state, None)

    d.binding match
      case None => SyncOutcome(d, SyncState.InSync, None)
      case Some(binding) =>
        val path   = binding.origin.filePath.map(Paths.get(_))
        // ONE read of the origin: it answers both questions below, and the Pull
        // branch reuses it rather than reading and re-hashing the same bytes.
        val origin = path.flatMap(Documents.read(_).toOption)
        val remote = origin.map(_.hash)

        // `base` and `remote` are hashes of FILE BYTES, so `local` has to be
        // measured the same way: the record's text as it would be written into
        // THIS file, using the convention that file already uses (V-04).
        //
        // Hashing with a fixed LF made every CRLF-authored origin read `Ahead`
        // forever — nothing had been edited, the bytes simply could not agree —
        // and made a byte-identical regeneration land on `Diverged` instead of
        // `Converged`, which is the conflict machine SyncState.Converged exists
        // to prevent. Hashing.ofText demands the convention explicitly for this
        // exact reason; see its scaladoc and V-16.
        //
        // With no origin on disk the state is OriginMissing whatever `local`
        // says, and Lf is what Documents.create would write if it reappears.
        val local = Hashing.ofText(d.text, origin.map(_.lineEnding).getOrElse(LineEnding.Lf))
        val state = SyncState.of(binding.baseHash, local, remote)

        binding.mode.autoAction(state) match
          case Some(SyncAction.Pull) =>
            (for doc <- origin
            yield
              val updated = d.copy(
                text = doc.text,
                binding = Some(binding.copy(baseHash = doc.hash, lastSyncAt = env.now())),
                updatedAt = env.now()
              )
              store(updated, state)
            ).getOrElse(SyncOutcome(d, state, None))

          case Some(SyncAction.Push) =>
            (for
              p   <- path
              doc <- Documents.write(p, d.text, binding.baseHash).toOption
            yield
              val updated =
                d.copy(binding = Some(binding.copy(baseHash = doc.hash, lastSyncAt = env.now())))
              env.audit.record(AuditEvent.Written(p.toString, doc.hash, "sync"))
              store(updated, state)
            ).getOrElse(SyncOutcome(d, state, None))

          case Some(SyncAction.AdvanceBase) =>
            // Converged: both sides moved to the same content, so only the
            // agreed baseline is stale. No I/O — this is what stops a
            // byte-identical regeneration from looking like a change.
            val updated = d.copy(binding = Some(binding.copy(baseHash = local, lastSyncAt = env.now())))
            store(updated, state)

          case None =>
            if state == SyncState.Diverged then
              env.audit.record(
                AuditEvent.Conflict(binding.origin.value, binding.baseHash, local, "sync")
              )
            // Nothing was written, so there is nothing that could fail to save.
            SyncOutcome(d, state, None)

  // -------------------------------------------------------------- watch

  /** Stream changes to stdout, one line per event.
    *
    * This is the headless primitive v1 lacked: a change stream a script or an
    * agent can pipe, with no window anywhere in the picture. `--open` is
    * accepted and warned about rather than silently ignored, which is what v1
    * did with it (the brief's §3: "`openInUi` is parsed into `_open_in_ui` and
    * discarded").
    */
  private def watch(args: Args, env: CliEnv): Int =
    // Resolve each ref ONCE. This used to run findInLibrary twice per argument
    // — once to collect the diagrams, once to test emptiness — and each call
    // scans the whole library.
    val (unresolved, found) = selectedRefs(args, env)
    val diagrams            = found.filter(_.isBound)

    // Only "nothing matched" can mean "this is a path"; an ambiguous ref is a
    // ref, and watching a FILE of that name is not what was asked for.
    val (ambiguous, missing) = unresolved.partitionMap:
      case err: RefError.Ambiguous => Left(err)
      case RefError.NotFound(ref)  => Right(ref)

    // A ref that is not in the library is a PATH, and every other path-taking
    // command runs it past the access policy first. watch did not, which made
    // it the one way to point gx at a file the policy forbids.
    val (denied, allowed) = missing.partitionMap(raw => checkPolicy(env.cwd.resolve(raw), env))

    if ambiguous.nonEmpty then ambiguous.map(reportRef(_, env)).last
    else if denied.nonEmpty then denied.head
    else
      val fromPaths = allowed.map(FileOrigins.originOf(_, env.cwd))
      val origins   = (diagrams.flatMap(_.binding.map(_.origin)) ++ fromPaths).distinct

      if origins.isEmpty then
        env.err("gx: nothing to watch")
        ExitCode.Usage
      else
        if args.has("open") && !env.desktopRunning() then
          env.err("gx: --open needs a running desktop; watching anyway")

        val interval = args.value("interval").flatMap(_.toLongOption).getOrElse(50L)
        val registry = WatchRegistry(env.audit, debounceMs = interval)
        origins.foreach(registry.watch)
        for o <- origins do env.err(s"watching ${o.value}")

        while env.keepWatching() do
          for event <- registry.poll() do emitWatchEvent(event, args, env)
          env.sleep(interval)
        ExitCode.Ok

  private def emitWatchEvent(event: WatchEvent, args: Args, env: CliEnv): Unit =
    val (kind, uri, hash) = event match
      case WatchEvent.Changed(u, h)  => ("changed", u, h)
      case WatchEvent.Restored(u, h) => ("restored", u, h)
      case WatchEvent.Deleted(u, h)  => ("deleted", u, h)
    if args.json then
      env.out(ujson.Obj("event" -> kind, "origin" -> uri.value, "hash" -> hash.hex).render())
    else env.out(s"$kind\t${uri.value}")

  // ---------------------------------------------------------------- run

  /** The document tier over a file (D7.2), headless.
    *
    * The same vocabulary the UI executes — `gx-core/command` defines it once and
    * this is the second of its three callers (D7.1). A mutation lands exactly
    * the way `gx set` lands text, through `applyText`, so "the same edit"
    * cannot mean two things depending on how it was expressed.
    */
  private def runCommand(args: Args, env: CliEnv): Int =
    if args.has("list") then
      // Discoverability is part of the vocabulary being a vocabulary: a name
      // nobody can enumerate is not addressable in any useful sense.
      listNames(AnyCommand.names, args, env)
    else
      (args.positionalAt(0), args.positionalAt(1)) match
        case (Some(_), Some(commandName)) =>
          paramsFrom(args, env) match
            case Left(why) => env.err(s"gx: $why"); ExitCode.Usage
            case Right(params) =>
              AnyCommand.decode(ujson.Obj("command" -> commandName, "params" -> params)) match
                case Left(CommandError.UnknownCommand(name)) =>
                  env.err(s"gx: unknown command '$name'")
                  env.err(s"gx: try one of: ${AnyCommand.names.mkString(", ")}")
                  ExitCode.Usage
                case Left(error) =>
                  env.err(s"gx: ${error.message}")
                  ExitCode.Usage
                case Right(command) =>
                  withTarget(args, env)(execute(_, command, args, env))
        case _ =>
          env.err("gx: run needs a diagram and a command")
          env.err(s"gx: commands: ${AnyCommand.names.mkString(", ")}")
          ExitCode.Usage

  private def execute(target: Target, command: AnyCommand, args: Args, env: CliEnv): Int =
    command match
      case AnyCommand.Doc(c) => executeDocument(target, c, args, env)
      case AnyCommand.Rec(c) => executeRecord(target, c, args, env)

  /** The record tier operates on stored METADATA, so it needs a record — and a
    * loose file on disk does not have one.
    *
    * Refused with the fix rather than with a shrug: `gx import` is what turns a
    * path into something that can carry tags and hidden elements, and saying so
    * is more useful than "not found".
    */
  private def executeRecord(target: Target, command: RecordCommand, args: Args, env: CliEnv): Int =
    target match
      case Target.OnDisk(path) =>
        env.err(s"gx: '${path.getFileName}' is not in the library, so it has no record to change")
        env.err(s"gx: import it first:  gx import ${path.getFileName}")
        ExitCode.InvalidPathOrPolicy

      case Target.InLibrary(d) =>
        RecordCommands.run(d, command) match
          case Left(error) =>
            env.err(s"gx: ${error.message}")
            ExitCode.InvalidPathOrPolicy

          case Right(RecordResult.Answered(value)) =>
            printAnswer(value, args, env)
            ExitCode.Ok

          case Right(RecordResult.Updated(updated)) =>
            // Metadata only: the record is saved, and the ORIGIN is untouched
            // whatever the sync mode says. That is §5.3.1's split doing its job
            // — hiding a node must never make a regenerating origin conflict.
            persist(updated.copy(updatedAt = env.now()), env): saved =>
              if args.json then env.out(summaryJson(saved).render(indent = 2))
              else env.out(s"${saved.id.value}  ${command.name}")
              ExitCode.Ok

  private def executeDocument(target: Target, command: DocumentCommand, args: Args, env: CliEnv): Int =
    textOf(target, env) match
      case Left(code) => code
      case Right(text) =>
        DiagramText.parse(text) match
          case Left(why) =>
            env.err(s"gx: $why")
            ExitCode.InvalidPathOrPolicy
          case Right(graph) =>
            DocumentCommands.run(graph, command) match
              case Left(error) =>
                // A refusal is the desktop-less equivalent of a greyed-out menu
                // item, and exit 4 is what the CLI already uses for "this
                // reference is not usable".
                env.err(s"gx: ${error.message}")
                ExitCode.InvalidPathOrPolicy

              case Right(CommandResult.Answered(value)) =>
                // A query never writes. This is the reason D4's channel is
                // request/response at all — "list the nodes" has an answer, and
                // no amount of watched state expresses one.
                printAnswer(value, args, env)
                ExitCode.Ok

              case Right(CommandResult.Updated(updated)) =>
                applyText(target, DiagramText.render(updated), args, env, source = "command")

  /** JSON for a machine, columns for a person — the same split `ls` makes.
    *
    * A query's answer is a `ujson.Value` because it crosses a wire, but a human
    * running `gx run x list-nodes` wants what `ls` gives them, not a JSON
    * document to read past.
    */
  private def printAnswer(value: ujson.Value, args: Args, env: CliEnv): Unit =
    val rows = value.arrOpt.toVector.flatten.flatMap(_.objOpt)
    if args.json || rows.isEmpty || !rows.forall(_.contains("ref")) then
      env.out(value.render(indent = 2))
    else
      for row <- rows do
        val ref = row.get("ref").flatMap(_.strOpt).getOrElse("")
        // `filterNot` rather than `- "ref"`: a ujson.Obj is backed by a MUTABLE
        // map, whose `-` is deprecated because it mutates in place. Removing a
        // key from the answer while printing it would be a fine way to make the
        // JSON and column forms disagree about what a row contains.
        val rest = row.toVector
          .filterNot((k, _) => k == "ref")
          // A null is the JSON form's way of saying "absent", and the column
          // form should say it by leaving the column out — `label=null` on every
          // unlabelled node is noise, and this listing is the one a person reads.
          .filterNot((_, v) => v.isNull)
          .map((k, v) => s"$k=${v.strOpt.getOrElse(v.render())}")
        env.out(if rest.isEmpty then ref else f"$ref%-28s ${rest.mkString("  ")}")

  private def textOf(target: Target, env: CliEnv): Either[Int, String] =
    target match
      case Target.InLibrary(d) => Right(d.text)
      case Target.OnDisk(path) =>
        Documents.read(path) match
          case Right(doc) => Right(doc.text)
          case Left(err) =>
            env.err(s"gx: ${describe(err)}")
            Left(ExitCode.InvalidPathOrPolicy)

  /** Params as JSON, from a flag or from stdin.
    *
    * `--stdin` matches `gx set`'s convention, and it is not a nicety: a
    * `set-attribute` carrying an HTML label, or a `group` over fifty nodes, is
    * past what belongs on a command line and well past what a shell will quote
    * correctly.
    */
  private def paramsFrom(args: Args, env: CliEnv): Either[String, ujson.Obj] =
    val raw =
      if args.has("stdin") then Some(env.stdin())
      else args.value("params")
    raw.map(_.trim).filter(_.nonEmpty) match
      case None => Right(ujson.Obj())
      case Some(text) =>
        try
          ujson.read(text).objOpt.map(ujson.Obj.from).toRight("params must be a JSON object")
        catch case NonFatal(e) => Left(s"params is not valid JSON: ${e.getMessage}")

  // ------------------------------------------------------------ session

  /** The session tier (D7.2): the live view, so a running desktop is required.
    *
    * No `<ref>` argument, and that is the tier's whole shape rather than an
    * omission — a session command acts on what is ON SCREEN, and the desktop
    * already knows what that is. Naming a diagram here would be asking a
    * question about a thing that may not be the thing being displayed.
    */
  private def sessionCommand(args: Args, env: CliEnv): Int =
    if args.has("list") then
      listNames(SessionCommand.names, args, env)
    else
      args.positionalAt(0) match
        case None =>
          env.err("gx: session needs a command")
          env.err(s"gx: commands: ${SessionCommand.names.mkString(", ")}")
          ExitCode.Usage
        case Some(commandName) =>
          paramsFrom(args, env) match
            case Left(why) => env.err(s"gx: $why"); ExitCode.Usage
            case Right(params) =>
              SessionCodec.decode(ujson.Obj("command" -> commandName, "params" -> params)) match
                case Left(CommandError.UnknownCommand(name)) =>
                  env.err(s"gx: unknown session command '$name'")
                  // The tiers are separate verbs, so naming the other one is
                  // the difference between a dead end and a next step.
                  if AnyCommand.names.contains(name) then
                    env.err(s"gx: '$name' is a headless command — try:  gx run <ref> $name")
                  else env.err(s"gx: try one of: ${SessionCommand.names.mkString(", ")}")
                  ExitCode.Usage

                case Left(error) =>
                  env.err(s"gx: ${error.message}")
                  ExitCode.Usage

                case Right(command) =>
                  env.rpc("session", SessionCodec.encode(command)) match
                    case Right(result) =>
                      if command.isQuery then printSessionAnswer(result, args, env)
                      else env.out(command.name)
                      ExitCode.Ok

                    case Left(ChannelError.NoDesktop(_)) =>
                      env.err("gx: no desktop is running, and the session tier needs one.")
                      env.err("gx: (every `gx run` command works without it)")
                      ExitCode.NeedsDesktop

                    case Left(ChannelError.Rpc(code, message, _)) =>
                      // NO_SESSION is "a desktop, but nothing on screen" — the
                      // tier's defining limit, and worth the same exit code as
                      // no desktop at all, since the caller's next move is the
                      // same: open something.
                      if code == "NO_SESSION" then
                        env.err(s"gx: $message")
                        ExitCode.NeedsDesktop
                      else
                        env.err(s"gx: the desktop refused it ($code): $message")
                        ExitCode.InvalidPathOrPolicy

                    case Left(ChannelError.Io(message)) =>
                      env.err(s"gx: control channel error: $message")
                      ExitCode.Unknown

  private def printSessionAnswer(result: ujson.Value, args: Args, env: CliEnv): Unit =
    if args.json then env.out(result.render(indent = 2))
    else
      result.arrOpt match
        case Some(items) if items.isEmpty => env.out("(nothing selected)")
        case Some(items)                  => items.flatMap(_.strOpt).foreach(env.out)
        case None                         => env.out(result.render(indent = 2))

  // --------------------------------------------------------------- open

  /** The one session-tier command (D7.2). It needs a live view, and there is no
    * live view without a window — a limit of the concept, not of the CLI.
    *
    * What it sends is `show`: watch the file AND raise the window. The desktop
    * treats that as `watch` plus focus rather than a separate operation, so a
    * diagram opened from the shell and one opened in the UI are the same state.
    */
  private def open(args: Args, env: CliEnv): Int =
    if args.positional.isEmpty then
      env.err("gx: open needs a diagram")
      ExitCode.Usage
    else
      // Resolved BEFORE the desktop is consulted: a typo'd name should say so,
      // not blame a missing window.
      openTarget(args, env) match
        case Left(code) => code
        case Right(target) =>
          // Typed, not a bare path (§3.1). Which KIND this is decides who owns
          // the document on the other side, and the desktop cannot infer that
          // from a path.
          val payload = target match
            case OpenTarget.Library(id) =>
              ujson.Obj("target" -> ujson.Obj("kind" -> "library", "diagramId" -> id.value))
            case OpenTarget.File(path) =>
              ujson.Obj("target" -> ujson.Obj("kind" -> "file", "path" -> path.toString))

          val shown = target match
            case OpenTarget.Library(id) => id.value
            case OpenTarget.File(path)  => path.toString

          env.rpc("show", payload) match
            case Right(result) =>
              val focused = result.objOpt.flatMap(_.get("focused")).flatMap(_.boolOpt).getOrElse(true)
              if args.json then
                env.out(
                  ujson
                    .Obj(
                      "kind"    -> (target match
                        case _: OpenTarget.Library => "library"
                        case _: OpenTarget.File    => "file"),
                      "ref"     -> shown,
                      "focused" -> focused
                    )
                    .render(indent = 2)
                )
              else
                env.out(s"showing $shown")
                // A desktop can be running with no window on screen. Saying so
                // beats reporting success for something the user cannot see.
                if !focused then env.err("gx: the desktop is running but has no window to raise")
              ExitCode.Ok

            case Left(ChannelError.NoDesktop(detail)) =>
              env.err("gx: no desktop is running. Start Graph Explorer Desktop, then retry.")
              env.err("gx: (every other gx command works without it)")
              env.err(s"gx: ($detail)")
              ExitCode.NeedsDesktop

            // A desktop that is up but has no window to show it in. Not the
            // same as refusing the path: nothing is wrong with what was asked,
            // and the user's next move is to bring the app up rather than to
            // fix an argument — which is what NeedsDesktop already means.
            case Left(ChannelError.Rpc("NO_WINDOW", message, _)) =>
              env.err(s"gx: the desktop is running but has no window to show it in ($message)")
              env.err("gx: open the Graph Explorer window, then retry")
              ExitCode.NeedsDesktop

            case Left(ChannelError.Rpc(code, message, _)) =>
              env.err(s"gx: the desktop refused to open it ($code): $message")
              ExitCode.InvalidPathOrPolicy

            case Left(ChannelError.Io(message)) =>
              env.err(s"gx: control channel error: $message")
              ExitCode.Unknown

  /** `open` takes the same references every other command does — an id, a name,
    * or a path — but the desktop only understands files, so a library diagram
    * has to resolve to the origin it is bound to.
    */
  private def openTarget(args: Args, env: CliEnv): Either[Int, OpenTarget] =
    val ref = args.positional.head

    // `--loose` is the escape hatch of §3.3: open the FILE at this path, even
    // if a record is bound to it. Without it there is no way to say "the file,
    // not the record" — and opening a loose file must never import it.
    if args.has("loose") then checkPolicy(env.cwd.resolve(ref), env).map(OpenTarget.File(_))
    else
      resolveRef(ref, env) match
        case Left(err: RefError.Ambiguous) => Left(reportRef(err, env))

        // A record opens AS a record, bound or not. It used to be refused when
        // it had no origin ("nothing to open"), which was only true while an
        // open could name nothing but a path: the record has text of its own
        // and is perfectly displayable (§3.2).
        case Right(d) => Right(OpenTarget.Library(d.id))

        case Left(_: RefError.NotFound) =>
          checkPolicy(env.cwd.resolve(ref), env).map(OpenTarget.File(_))

  // -------------------------------------------------------------- skill

  /** Print where the agent skill lives, and the sentence to hand an agent.
    *
    * Deliberately NOT an installer. The skill is a prompt that a coding agent
    * will load and act on, so writing it into someone's agent directory is a
    * decision rather than a side effect of asking where it is — and every
    * harness keeps skills somewhere different anyway. Printing a location plus
    * the instruction works for all of them and leaves the human in the loop.
    *
    * Pinned to this binary by default. The skill names commands, param keys and
    * exit codes, all of which are API that moves between releases, so an agent
    * reading the branch tip while driving an older `gx` would be reading about
    * commands it does not have.
    */
  private def skill(args: Args, env: CliEnv): Int =
    SkillLocation.resolve(
      requested = args.positionalAt(0),
      latest = args.has("latest"),
      running = buildinfo.BuildInfo.version
    ) match
      case Left(why) =>
        env.err(s"gx: $why")
        ExitCode.Usage

      case Right(found) =>
        if args.json then
          env.out(
            ujson.Obj(
              "skill"   -> SkillLocation.Name,
              "version" -> found.version,
              "ref"     -> found.ref,
              "pinned"  -> found.pinned,
              "page"    -> found.page,
              "raw"     -> found.raw
            ).render(indent = 2)
          )
        else
          env.out(s"gx ${found.version} — agent skill '${SkillLocation.Name}'")
          env.out("")
          env.out(s"  browse:  ${found.page}")
          env.out(s"  fetch:   ${found.raw}")
          env.out("")
          env.out("Tell your coding agent:")
          env.out("")
          // Second person, addressed to the agent rather than about it, so the
          // block can be pasted straight into a prompt. The URL gets a line of
          // its own: it is the one part that must survive being copied out of a
          // wrapped terminal intact.
          env.out("  Read and analyze the skill at")
          env.out(s"    ${found.raw}")
          env.out(s"  together with ${SkillLocation.SupportingFiles.mkString(" and ")} beside it, which it links.")
          env.out("  Check it against the `gx` on this machine, then install all three as a")
          env.out(s"  skill named `${SkillLocation.Name}` wherever this harness keeps skills — for Claude")
          env.out(s"  Code that is ${SkillLocation.Name}/ under .claude/skills/ in the project, or under")
          env.out("  your home directory to have it everywhere. Keep the frontmatter intact.")
          env.out("")
          if found.pinned then
            env.out(s"Pinned to ${found.ref}, the gx you are running.")
            env.out("`gx skill --latest` prints the branch tip instead.")
          else
            env.out(s"This gx (${found.version}) is not a released version, so this is the tip of")
            env.out(s"`${SkillLocation.DefaultBranch}` and may describe commands it does not have.")
            SkillLocation.baseRelease(found.version) match
              case Some(base) => env.out(s"Pin it to a release instead:  gx skill $base")
              case None       => env.out("Pin it to a release instead:  gx skill <version>")
        ExitCode.Ok

  // ---------------------------------------------------------- resolution

  private def withTarget(args: Args, env: CliEnv)(f: Target => Int): Int =
    args.positionalAt(0) match
      case None =>
        env.err("gx: this command needs a diagram or a path")
        ExitCode.Usage
      case Some(ref) =>
        resolveRef(ref, env) match
          case Right(d) => f(Target.InLibrary(d))
          // Only "nothing matched" can mean "this is a path". An ambiguous ref
          // falling through here is how `gx set <ambiguous>` ended up writing
          // to a FILE of that name instead of refusing.
          case Left(err: RefError.Ambiguous) => reportRef(err, env)
          case Left(_: RefError.NotFound) =>
            val path = env.cwd.resolve(ref)
            checkPolicy(path, env) match
              case Left(code) => code
              case Right(resolved) => f(Target.OnDisk(resolved))

  /** id, then exact name, then the origin path. Ambiguity is reported rather
    * than resolved by picking one, because "gx set" on the wrong diagram is not
    * a mistake the user can see happening.
    *
    * That promise used to be unkept: this returned None for "nothing matched"
    * AND for "several matched", so every caller rendered both as "no diagram
    * matches" — and the ones that fall back to treating the ref as a PATH did
    * so on an ambiguous name, which is the wrong-diagram write the paragraph
    * above is about.
    */
  private def resolveRef(ref: String, env: CliEnv): Either[RefError, Diagram] =
    val all = env.store.list()
    lazy val byName = all.filter(_.name == ref)
    lazy val byOrigin =
      val origin = FileOrigins.originOf(env.cwd.resolve(ref), env.cwd)
      all.filter(_.binding.exists(_.origin == origin))

    all.find(_.id.value == ref) match
      case Some(d) => Right(d)
      case None =>
        // Tier order is preserved: a unique name beats an origin match, and an
        // origin match still answers when the name tier found nothing usable.
        (byName, byOrigin) match
          case (Vector(one), _)     => Right(one)
          case (_, Vector(one))     => Right(one)
          case (Vector(), Vector()) => Left(RefError.NotFound(ref))
          case (many, others)       => Left(RefError.Ambiguous(ref, (many ++ others).distinct))

  /** True when the ref names something in the library. Callers that fall back
    * to a path use this; an AMBIGUOUS ref is not a path and must not fall
    * through, so they check [[resolveRef]] rather than this.
    */
  private def findInLibrary(ref: String, env: CliEnv): Option[Diagram] =
    resolveRef(ref, env).toOption

  private def reportRef(err: RefError, env: CliEnv): Int = err match
    case RefError.NotFound(ref) =>
      env.err(s"gx: no diagram matches '$ref'")
      ExitCode.InvalidPathOrPolicy
    case RefError.Ambiguous(ref, matches) =>
      env.err(s"gx: '$ref' matches ${matches.size} diagrams — name one by id:")
      for d <- matches do env.err(s"gx:   ${d.id.value}  ${d.name}")
      ExitCode.InvalidPathOrPolicy

  // ------------------------------------------------------------- helpers

  /** Save, or report the failure the same way everywhere. A store write that
    * fails is `Unknown`, not a success with a warning — the six call sites that
    * spelled this out by hand could each have answered differently.
    */
  private def persist(d: Diagram, env: CliEnv)(onSaved: Diagram => Int): Int =
    env.store.save(d) match
      case Left(e)      => env.err(s"gx: $e"); ExitCode.Unknown
      case Right(saved) => onSaved(saved)

  /** The vocabulary of a command tier, for `--list`. Both tiers print it the
    * same way; two copies is how the two `--list` flags start differing.
    */
  private def listNames(names: Vector[String], args: Args, env: CliEnv): Int =
    if args.json then env.out(ujson.Arr.from(names.map(ujson.Str(_))).render(indent = 2))
    else names.foreach(env.out)
    ExitCode.Ok

  /** `--all`, or no arguments at all, means the whole library; otherwise the
    * positional refs, resolved. `sync` and `watch` are documented as sharing
    * that rule, so they read it from here rather than each restating it.
    *
    * Failures are RETURNED, not dropped. Flat-mapping them away is what made
    * `gx sync typo` print "(nothing bound to sync)" and exit 0 — a script that
    * mistyped a name was told it had succeeded.
    */
  private def selectedRefs(args: Args, env: CliEnv): (Vector[RefError], Vector[Diagram]) =
    if args.has("all") || args.positional.isEmpty then (Vector.empty, env.store.list())
    else args.positional.partitionMap(resolveRef(_, env))

  private def checkPolicy(path: Path, env: CliEnv): Either[Int, Path] =
    env.policy.evaluate(path, env.cwd) match
      case Right(resolved) => Right(resolved)
      case Left(denial) =>
        env.audit.record(AuditEvent.Denied(denial.path, denial.reason))
        env.err(s"gx: ${denial.reason}")
        Left(ExitCode.InvalidPathOrPolicy)

  private def conflict(path: String, expected: ContentHash, actual: ContentHash, env: CliEnv): Int =
    env.audit.record(AuditEvent.Conflict(path, expected, actual, "cli"))
    env.err(s"gx: conflict — $path changed underneath this write")
    env.err(s"gx:   expected ${expected.hex.take(12)}, found ${actual.hex.take(12)}")
    ExitCode.Conflict

  private def textFrom(args: Args, env: CliEnv): Either[String, String] =
    (args.has("stdin"), args.value("text")) match
      case (true, _)        => Right(env.stdin())
      case (false, Some(t)) => Right(t)
      case (false, None)    => Left("provide --stdin or --text")

  private def modeOf(args: Args, default: SyncMode): Either[String, SyncMode] =
    args.value("mode") match
      case None => Right(default)
      case Some(raw) =>
        SyncMode.values.find(_.toString.equalsIgnoreCase(raw)) match
          case Some(m) => Right(m)
          case None =>
            Left(s"unknown mode '$raw' (expected ${SyncMode.values.map(_.toString.toLowerCase).mkString(", ")})")

  private def detectFormat(text: String): String =
    org.jpablo.graphexplorer.viewer.backends.DiagramFormat.detect(text).toString

  private def defaultName(path: Path): String =
    val n = path.getFileName.toString
    val i = n.lastIndexOf('.')
    if i > 0 then n.substring(0, i) else n

  private def freshId(name: String, env: CliEnv): String =
    val slug = name.map(c => if c.isLetterOrDigit then c.toLower else '-').take(40)
    val base = if slug.isEmpty then "diagram" else slug
    if !env.store.contains(DiagramId(base)) then base
    else LazyList.from(2).map(i => s"$base-$i").find(s => !env.store.contains(DiagramId(s))).getOrElse(base)

  private def describe(err: DocumentError): String = err match
    case DocumentError.NotFound(p)         => s"no such file: $p"
    case DocumentError.NotAFile(p)         => s"not a regular file: $p"
    case DocumentError.AlreadyExists(p)    => s"already exists: $p"
    case DocumentError.Io(p, m)            => s"$p: $m"
    case DocumentError.Conflict(p, _, _)   => s"conflict on $p"

  private def summaryJson(d: Diagram): ujson.Obj =
    ujson.Obj(
      "id"     -> d.id.value,
      "name"   -> d.name,
      "folder" -> d.folder.render,
      "format" -> d.format,
      "origin" -> d.binding.map(b => ujson.Str(b.origin.value)).getOrElse(ujson.Null),
      "mode"   -> d.binding.map(b => ujson.Str(b.mode.toString)).getOrElse(ujson.Null)
    )

  private def fileJson(path: Path, hash: ContentHash): ujson.Obj =
    ujson.Obj("path" -> path.toString, "hash" -> hash.hex)

  private def printDiagram(d: Diagram, args: Args, env: CliEnv): Unit =
    if args.json then env.out(summaryJson(d).render(indent = 2))
    else
      env.out(s"id:     ${d.id.value}")
      env.out(s"name:   ${d.name}")
      env.out(s"folder: ${d.folder.render}")
      d.binding.foreach: b =>
        env.out(s"origin: ${b.origin.value}")
        env.out(s"mode:   ${b.mode}")

  private def reportSet(d: Diagram, wroteOrigin: Boolean, args: Args, env: CliEnv): Unit =
    if args.json then
      env.out(ujson.Obj("id" -> d.id.value, "wroteOrigin" -> wroteOrigin).render(indent = 2))
    else if wroteOrigin then env.out(s"${d.id.value}  written to ${d.binding.map(_.origin.value).getOrElse("")}")
    else
      env.out(s"${d.id.value}  saved locally")
      d.binding.foreach: b =>
        if !b.mode.pushes then
          env.out(s"(${b.mode} does not write back; the origin is unchanged)")
