package org.jpablo.graphexplorer.gx

import org.jpablo.graphexplorer.gxcore.fs.*
import org.jpablo.graphexplorer.gxcore.model.*
import org.jpablo.graphexplorer.gxcore.rpc.ChannelError

import java.nio.file.{Path, Paths}
import scala.util.control.NonFatal

/** What a reference on the command line turned out to mean. */
private enum Target derives CanEqual:
  case InLibrary(diagram: Diagram)

  /** A path that is not in the library. Still perfectly usable: under D1 a
    * document's revision is the hash of its bytes, so reading and conditionally
    * writing a file needs no registration first. v1 could not do this — `get`
    * failed with "path is not currently watched" until you had called `watch`.
    */
  case OnDisk(path: Path, origin: OriginUri)

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
      |  gx open <ref>                          show it in the desktop
      |
      |  M = detached | pull | push | sync      (default: pull)
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
      case "open"   => open(args, env)
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
    val running = desktop.isDefined
    val watches = desktop.flatMap(_.get("watches")).flatMap(_.arrOpt).map(_.size).getOrElse(0)

    if args.json then
      env.out(
        ujson.Obj(
          "ok"             -> true,
          "library"        -> env.store.root.toString,
          "diagrams"       -> diagrams.size,
          "bound"          -> bound,
          "desktopRunning" -> running,
          "desktopWatches" -> watches
        ).render(indent = 2)
      )
    else
      env.out(s"library:  ${env.store.root}")
      env.out(s"diagrams: ${diagrams.size} ($bound bound)")
      if running then env.out(s"desktop:  running ($watches open)")
      else env.out("desktop:  not running (only `gx open` needs it)")
    ExitCode.Ok

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
                          env.store.initialize()
                          env.store.save(d) match
                            case Left(e) => env.err(s"gx: $e"); ExitCode.Unknown
                            case Right(saved) =>
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
        case Target.OnDisk(path, _) =>
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
        withTarget(args, env): target =>
          target match
            case Target.OnDisk(path, _) => writeFile(path, text, args, env, source = "cli")
            case Target.InLibrary(d) =>
              val updated = d.copy(text = text, updatedAt = env.now())
              d.binding.filter(b => b.mode.pushes) match
                case None =>
                  // Local-only by mode: Pull keeps UI/CLI edits in the store and
                  // never writes them back (§5.3). Saving the record is the
                  // whole operation, and the divergence it creates is reported
                  // by `gx sync` rather than resolved behind the user's back.
                  env.store.save(updated) match
                    case Left(e)  => env.err(s"gx: $e"); ExitCode.Unknown
                    case Right(_) => reportSet(updated, wroteOrigin = false, args, env); ExitCode.Ok
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
                          env.store.save(synced) match
                            case Left(e) => env.err(s"gx: $e"); ExitCode.Unknown
                            case Right(_) =>
                              env.audit.record(AuditEvent.Written(path.toString, doc.hash, "cli"))
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
            findInLibrary(ref, env) match
              case None =>
                env.err(s"gx: no diagram matches '$ref'")
                ExitCode.InvalidPathOrPolicy
              case Some(d) =>
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
                        env.store.save(bound) match
                          case Left(e)  => env.err(s"gx: $e"); ExitCode.Unknown
                          case Right(_) => printDiagram(bound, args, env); ExitCode.Ok
      case _ =>
        env.err("gx: bind needs a diagram and a path")
        ExitCode.Usage

  private def unbind(args: Args, env: CliEnv): Int =
    args.positionalAt(0).flatMap(findInLibrary(_, env)) match
      case None =>
        env.err("gx: unbind needs a diagram")
        ExitCode.InvalidPathOrPolicy
      case Some(d) =>
        val detached = d.copy(binding = None, updatedAt = env.now())
        env.store.save(detached) match
          case Left(e)  => env.err(s"gx: $e"); ExitCode.Unknown
          case Right(_) => printDiagram(detached, args, env); ExitCode.Ok

  // --------------------------------------------------------------- sync

  private def sync(args: Args, env: CliEnv): Int =
    val targets =
      if args.has("all") || args.positional.isEmpty then env.store.list().filter(_.isBound)
      else args.positional.flatMap(findInLibrary(_, env)).filter(_.isBound)

    if targets.isEmpty then
      env.out(if args.json then "[]" else "(nothing bound to sync)")
      ExitCode.Ok
    else
      val results  = targets.map(syncOne(_, env))
      val diverged = results.count(_._2 == SyncState.Diverged)
      if args.json then
        env.out(
          ujson.Arr
            .from(results.map((d, state) =>
              ujson.Obj("id" -> d.id.value, "state" -> state.toString)
            ))
            .render(indent = 2)
        )
      else for (d, state) <- results do env.out(f"${d.id.value}%-28s $state")
      // Divergence is a state, not an error (§5.2) — but a script that just
      // pushed and wants to know whether it landed deserves a non-zero code.
      if diverged > 0 then ExitCode.Conflict else ExitCode.Ok

  private def syncOne(d: Diagram, env: CliEnv): (Diagram, SyncState) =
    d.binding match
      case None => (d, SyncState.InSync)
      case Some(binding) =>
        val path   = binding.origin.filePath.map(Paths.get(_))
        val remote = path.flatMap(Documents.hashOf)
        val local  = Hashing.ofText(d.text, LineEnding.Lf)
        val state  = SyncState.of(binding.baseHash, local, remote)

        binding.mode.autoAction(state) match
          case Some(SyncAction.Pull) =>
            (for
              p   <- path
              doc <- Documents.read(p).toOption
            yield
              val updated = d.copy(
                text = doc.text,
                binding = Some(binding.copy(baseHash = doc.hash, lastSyncAt = env.now())),
                updatedAt = env.now()
              )
              env.store.save(updated)
              (updated, state)
            ).getOrElse((d, state))

          case Some(SyncAction.Push) =>
            (for
              p   <- path
              doc <- Documents.write(p, d.text, binding.baseHash).toOption
            yield
              val updated =
                d.copy(binding = Some(binding.copy(baseHash = doc.hash, lastSyncAt = env.now())))
              env.store.save(updated)
              env.audit.record(AuditEvent.Written(p.toString, doc.hash, "sync"))
              (updated, state)
            ).getOrElse((d, state))

          case Some(SyncAction.AdvanceBase) =>
            // Converged: both sides moved to the same content, so only the
            // agreed baseline is stale. No I/O — this is what stops a
            // byte-identical regeneration from looking like a change.
            val updated = d.copy(binding = Some(binding.copy(baseHash = local, lastSyncAt = env.now())))
            env.store.save(updated)
            (updated, state)

          case None =>
            if state == SyncState.Diverged then
              env.audit.record(
                AuditEvent.Conflict(binding.origin.value, binding.baseHash, local, "sync")
              )
            (d, state)

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
    val diagrams =
      if args.has("all") || args.positional.isEmpty then env.store.list().filter(_.isBound)
      else args.positional.flatMap(findInLibrary(_, env)).filter(_.isBound)

    val fromPaths = args.positional.filter(r => findInLibrary(r, env).isEmpty).map: raw =>
      FileOrigins.originOf(env.cwd.resolve(raw), env.cwd)

    val origins = (diagrams.flatMap(_.binding.map(_.origin)) ++ fromPaths).distinct

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
      case WatchEvent.Changed(u, h)   => ("changed", u, Some(h))
      case WatchEvent.Restored(u, h)  => ("restored", u, Some(h))
      case WatchEvent.Deleted(u, h)   => ("deleted", u, Some(h))
    if args.json then
      env.out(
        ujson
          .Obj("event" -> kind, "origin" -> uri.value, "hash" -> hash.map(_.hex).getOrElse(""))
          .render()
      )
    else env.out(s"$kind\t${uri.value}")

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
      pathToShow(args, env) match
        case Left(code) => code
        case Right(path) =>
          env.rpc("show", ujson.Obj("path" -> path.toString)) match
            case Right(result) =>
              val focused = result.objOpt.flatMap(_.get("focused")).flatMap(_.boolOpt).getOrElse(true)
              if args.json then
                env.out(ujson.Obj("path" -> path.toString, "focused" -> focused).render(indent = 2))
              else
                env.out(s"showing ${path}")
                // A desktop can be running with no window on screen. Saying so
                // beats reporting success for something the user cannot see.
                if !focused then env.err("gx: the desktop is running but has no window to raise")
              ExitCode.Ok

            case Left(ChannelError.NoDesktop(detail)) =>
              env.err("gx: no desktop is running. Start Graph Explorer Desktop, then retry.")
              env.err("gx: (every other gx command works without it)")
              env.err(s"gx: ($detail)")
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
  private def pathToShow(args: Args, env: CliEnv): Either[Int, Path] =
    val ref = args.positional.head
    findInLibrary(ref, env) match
      case Some(d) =>
        d.binding.flatMap(_.origin.filePath).map(Paths.get(_)) match
          case Some(path) => Right(path)
          case None =>
            env.err(s"gx: '${d.name}' is not bound to a file, so there is nothing to open")
            env.err(s"gx: bind it first:  gx bind ${d.id.value} <path>")
            Left(ExitCode.InvalidPathOrPolicy)
      case None =>
        checkPolicy(env.cwd.resolve(ref), env).left.map(identity)

  // ---------------------------------------------------------- resolution

  private def withTarget(args: Args, env: CliEnv)(f: Target => Int): Int =
    args.positionalAt(0) match
      case None =>
        env.err("gx: this command needs a diagram or a path")
        ExitCode.Usage
      case Some(ref) =>
        findInLibrary(ref, env) match
          case Some(d) => f(Target.InLibrary(d))
          case None =>
            val path = env.cwd.resolve(ref)
            checkPolicy(path, env) match
              case Left(code) => code
              case Right(resolved) => f(Target.OnDisk(resolved, FileOrigins.originOf(resolved, env.cwd)))

  /** id, then exact name, then the origin path. Ambiguity is reported rather
    * than resolved by picking one, because "gx set" on the wrong diagram is not
    * a mistake the user can see happening.
    */
  private def findInLibrary(ref: String, env: CliEnv): Option[Diagram] =
    val all = env.store.list()
    all
      .find(_.id.value == ref)
      .orElse:
        all.filter(_.name == ref) match
          case Vector(one) => Some(one)
          case _           => None
      .orElse:
        val origin = FileOrigins.originOf(env.cwd.resolve(ref), env.cwd)
        all.filter(_.binding.exists(_.origin == origin)) match
          case Vector(one) => Some(one)
          case _           => None

  // ------------------------------------------------------------- helpers

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
