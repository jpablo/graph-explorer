package org.jpablo.graphexplorer.gx

import org.jpablo.graphexplorer.gxcore.command.{
  DiagramText,
  ElementName,
  ElementNames,
  ElementRef,
  LiveViewCodec,
  LiveViewCommand,
  LiveViewSelection,
  MetadataCommand,
  MetadataCommands,
  SourceCommand,
  SourceCommands
}
import org.jpablo.graphexplorer.gxcore.fs.*
import org.jpablo.graphexplorer.gxcore.model.{ContentHash, DiagramMetadata, LibraryEntry}
import org.jpablo.graphexplorer.gxcore.rpc.ChannelError
import org.jpablo.graphexplorer.gxcore.store.{MetadataError, MetadataMigrationExecutor, MetadataMigrationResult}
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat, RoundTripLoss, UnmodeledContent}
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.{Cluster, Label, Name}
import org.jpablo.graphexplorer.viewer.graph.ViewerGraph
import org.jpablo.graphexplorer.viewer.models.{ArrowId, ElementId, GroupId, NodeId, ViewerGroup}

import java.nio.file.{Files, Path, Paths}
import scala.util.control.NonFatal

/** The `gx` command surface (plan §2).
  *
  * Each command is a verb, and the diagram FILE comes first. The file is the
  * only copy of the source of a diagram (ADR 0001):
  *
  *   - A read command (`nodes`, `arrows`, `groups`, `attrs`) parses the file.
  *   - A change command (`set`, `group`, …) writes the file. It writes only
  *     when the file still has the hash that gx read. It refuses a write that
  *     removes comments or `strict`, unless the caller adds `--reformat`.
  *   - A metadata command (`hide`, `tag`, …) writes the library entry of the
  *     file, and never the file.
  *   - A desktop command (`open`, `select`, `selection`, `fit`) needs a running
  *     desktop. Each other command works with no desktop.
  *
  * Most callers are agents. Thus each result has `--json`, and each error
  * names what is wrong and what to do.
  */
object Cli:

  private val Usage =
    """gx — Graph Explorer command line
      |
      |Read a diagram
      |  gx nodes  <file> [--json]
      |  gx arrows <file> [--json]
      |  gx groups <file> [--json]
      |  gx attrs  <file> <element>... [--json]
      |
      |Change a diagram (gx writes the file)
      |  gx set     <file> <element>... <name>=<value>...    (<name>= removes the attribute)
      |  gx reset   <file> <element>...           remove each attribute except the label
      |  gx group   <file> <element>... [--label L]
      |  gx ungroup <file> <group>...
      |  gx combine-record   <file> <node>...
      |  gx split-record     <file> <node>
      |  gx transpose-record <file> <node>
      |
      |Remember about a diagram (gx writes the metadata, never the file)
      |  gx hide     <file> <element>...    gx unhide <file> (<element>... | --all)
      |  gx collapse <file> <group>...      gx expand <file> (<group>... | --all)
      |  gx tag      <file> <tag>...        gx untag  <file> <tag>...
      |  gx note     <file> (<text> | --stdin | --clear)
      |  gx info     <file> [--json]        the metadata of a diagram
      |  gx ls [--tag T] [--json]           the library
      |  gx add <file>...                   put a diagram in the library
      |  gx remove <file>...                take it out (the file stays)
      |
      |The desktop app
      |  gx open      <file>
      |  gx select    <file> <element>...
      |  gx selection [--json]              what is selected, and in which diagram
      |  gx fit       <file>                show the whole diagram
      |
      |Other
      |  gx status    gx watch <file>... [--json]    gx skill    gx --version
      |""".stripMargin

  // ------------------------------------------------------- command table

  /** One command: its name, its synopsis for an error message, the flags that
    * it takes, and its body. `library` is true for a command that reads or
    * writes the library. The migration runs before such a command.
    */
  private final case class Command(
      name:     String,
      synopsis: String,
      switches: Set[String],
      values:   Set[String],
      library:  Boolean,
      body:     (Command, Args, CliEnv) => Int
  )

  private val Json   = Set("json")
  private val Change = Set("json", "reformat")

  private val Commands: Vector[Command] = Vector(
    Command("nodes", "gx nodes <file> [--json]", Json, Set.empty, false, readNodes),
    Command("arrows", "gx arrows <file> [--json]", Json, Set.empty, false, readArrows),
    Command("groups", "gx groups <file> [--json]", Json, Set.empty, false, readGroups),
    Command("attrs", "gx attrs <file> <element>... [--json]", Json, Set.empty, false, readAttrs),
    Command("set", "gx set <file> <element>... <name>=<value>...", Change, Set.empty, false, setAttributes),
    Command("reset", "gx reset <file> <element>...", Change, Set.empty, false, reset),
    Command("group", "gx group <file> <element>... [--label L]", Change, Set("label"), false, group),
    Command("ungroup", "gx ungroup <file> <group>...", Change, Set.empty, false, ungroup),
    Command("combine-record", "gx combine-record <file> <node>...", Change, Set.empty, false, combineRecord),
    Command("split-record", "gx split-record <file> <node>", Change, Set.empty, false, splitRecord),
    Command("transpose-record", "gx transpose-record <file> <node>", Change, Set.empty, false, transposeRecord),
    Command("hide", "gx hide <file> <element>...", Json, Set.empty, true, hide),
    Command("unhide", "gx unhide <file> (<element>... | --all)", Json + "all", Set.empty, true, unhide),
    Command("collapse", "gx collapse <file> <group>...", Json, Set.empty, true, collapse),
    Command("expand", "gx expand <file> (<group>... | --all)", Json + "all", Set.empty, true, expand),
    Command("tag", "gx tag <file> <tag>...", Json, Set.empty, true, tag),
    Command("untag", "gx untag <file> <tag>...", Json, Set.empty, true, untag),
    Command("note", "gx note <file> (<text> | --stdin | --clear)", Json ++ Set("stdin", "clear"), Set.empty, true, note),
    Command("info", "gx info <file> [--json]", Json, Set.empty, true, info),
    Command("ls", "gx ls [--tag T] [--json]", Json, Set("tag"), true, ls),
    Command("add", "gx add <file>... [--json]", Json, Set.empty, true, add),
    Command("remove", "gx remove <file>... [--json]", Json, Set.empty, true, remove),
    Command("open", "gx open <file> [--json]", Json, Set.empty, false, open),
    Command("select", "gx select <file> <element>... [--json]", Json, Set.empty, false, select),
    Command("selection", "gx selection [--json]", Json, Set.empty, false, selection),
    Command("fit", "gx fit <file> [--json]", Json, Set.empty, false, fit),
    Command("status", "gx status [--json]", Json, Set.empty, true, status),
    Command("watch", "gx watch <file>... [--json] [--interval MS]", Json, Set("interval"), false, watch),
    Command("skill", "gx skill [<version>] [--latest] [--json]", Json + "latest", Set.empty, false, skill)
  )

  /** The commands that plan §2 removes, and what to use instead. An old name
    * fails with one line, so an agent with an old habit learns the new verb.
    * The old `gx run` command names are here too.
    */
  private val Gone: Map[String, String] = Map(
    "import"           -> "use 'gx add <file>'",
    "get"              -> "read the file itself, or use 'gx nodes <file>' and 'gx attrs <file> <element>...'",
    "bind"             -> "the file is the diagram; use 'gx add <file>'",
    "unbind"           -> "use 'gx remove <file>'",
    "sync"             -> "the file is the only copy of a diagram, so there is nothing to sync",
    "run"              -> "use a verb such as 'gx set <file> …'",
    "view"             -> "use 'gx select', 'gx selection' or 'gx fit'",
    "session"          -> "use 'gx select', 'gx selection' or 'gx fit'",
    "push-text"        -> "write the file; the desktop follows the file",
    "unset"            -> "use 'gx set <file> <element>... <name>='",
    "rename-diagram"   -> "rename the file, or use 'gx tag <file> <tag>'",
    "move-to-folder"   -> "move the file, or use 'gx tag <file> <tag>'",
    "list-nodes"       -> "use 'gx nodes <file>'",
    "list-arrows"      -> "use 'gx arrows <file>'",
    "list-groups"      -> "use 'gx groups <file>'",
    "get-attributes"   -> "use 'gx attrs <file> <element>...'",
    "set-attribute"    -> "use 'gx set <file> <element>... <name>=<value>'",
    "remove-attribute" -> "use 'gx set <file> <element>... <name>='",
    "get-metadata"     -> "use 'gx info <file>'",
    "set-notes"        -> "use 'gx note <file> <text>'",
    "what-is-selected" -> "use 'gx selection'",
    "reset-view"       -> "use 'gx fit <file>'"
  )

  /** The flags that plan §2 removes, and what to use instead. */
  private val GoneFlags: Map[String, String] = Map(
    "mode"   -> "the file is the only copy of a diagram, so there is no sync mode",
    "params" -> "give the arguments as words, for example 'gx set <file> api color=red'",
    "base"   -> "gx reads the hash of the file itself, and a conflict is exit 5",
    "loose"  -> "'gx open <file>' always opens the file",
    "folder" -> "use tags: 'gx tag <file> <tag>' and 'gx ls --tag <tag>'",
    "text"   -> "write the file yourself, or use 'gx set <file> <element>... <name>=<value>...'",
    "stdin"  -> "write the file yourself, or use 'gx set <file> <element>... <name>=<value>...'",
    "name"   -> "the name of a diagram is the title in its source, or else its file name",
    "list"   -> "'gx help' lists the commands",
    "open"   -> "use 'gx open <file>'"
  )

  // ------------------------------------------------------------- entry

  def run(argv: Vector[String], env: CliEnv): Int =
    argv.headOption match
      case None =>
        env.out(Usage)
        ExitCode.Usage

      case Some("--help" | "-h" | "help") =>
        env.out(Usage)
        ExitCode.Ok

      case Some("--version" | "version") =>
        env.out(buildinfo.BuildInfo.version)
        ExitCode.Ok

      case Some(name) if Gone.contains(name) =>
        env.err(s"gx: '$name' is gone; ${Gone(name)} (see 'gx help')")
        ExitCode.Usage

      case Some(name) =>
        Commands.find(_.name == name) match
          case None =>
            env.err(s"gx: unknown command '$name'. 'gx help' lists the commands.")
            ExitCode.Usage
          case Some(command) =>
            Args.parse(argv.drop(1)) match
              case Left(why) =>
                env.err(s"gx: $why")
                ExitCode.Usage
              case Right(args) =>
                checkFlags(command, args, env).getOrElse:
                  try
                    val stopped = if command.library then migrate(env) else None
                    stopped.getOrElse(command.body(command, args, env))
                  catch
                    case NonFatal(e) =>
                      env.err(s"gx: unexpected failure: $e")
                      ExitCode.Unknown

  /** Refuse a flag that the command does not take, with one line. A removed
    * flag names its replacement. An unknown flag names the flags that the
    * command takes.
    */
  private def checkFlags(command: Command, args: Args, env: CliEnv): Option[Int] =
    val bad =
      args.switches.toVector.sorted.find(s => !command.switches.contains(s)) orElse
        args.values.keys.toVector.sorted.find(v => !command.values.contains(v))
    bad.map: flag =>
      GoneFlags.get(flag) match
        case Some(instead) => env.err(s"gx: '--$flag' is gone; $instead (see 'gx help')")
        case None if command.switches.contains(flag) =>
          env.err(s"gx: --$flag takes no value; usage: ${command.synopsis}")
        case None if command.values.contains(flag) =>
          env.err(s"gx: --$flag needs a value; usage: ${command.synopsis}")
        case None =>
          val takes = (command.switches ++ command.values).toVector.sorted.map("--" + _)
          val list  = if takes.isEmpty then "no flags" else takes.mkString(", ")
          env.err(s"gx: '${command.name}' does not take --$flag; it takes $list")
      ExitCode.Usage

  /** The one-time migration of plan §4. It runs before a library command, and
    * only while `metadata/` does not exist. `None` means "continue".
    */
  private def migrate(env: CliEnv): Option[Int] =
    val target = GxHome.metadataDir(env.gxHome)
    MetadataMigrationExecutor.run(env.gxHome, env.diagramsDir, env.now) match
      case MetadataMigrationResult.AlreadyDone => None
      case MetadataMigrationResult.Done(report) =>
        if report.nonEmpty then
          env.err(s"gx: the library moved to $target (one time). The old records in diagrams/ stay unchanged.")
          report.foreach(line => env.err(s"gx:   $line"))
        None
      case MetadataMigrationResult.Failed(reason, report) =>
        env.err(s"gx: the one-time move of the library to $target failed: $reason")
        report.foreach(line => env.err(s"gx:   $line"))
        env.err("gx: the old library is unchanged. Correct the cause, then run the command again.")
        Some(ExitCode.Unknown)

  // --------------------------------------------------------- the file

  /** The diagram file of a command: the first argument, resolved against the
    * working directory of the user, checked by the policy, and canonical.
    */
  private def fileArg(command: Command, args: Args, env: CliEnv): Either[Int, Path] =
    args.positionalAt(0) match
      case None =>
        env.err(s"gx: ${command.name} needs a <file>; usage: ${command.synopsis}")
        Left(ExitCode.Usage)
      case Some(raw) => checkPolicy(raw, env)

  /** The policy evaluates the canonical path: symlinks resolved, and one
    * spelling for each file (`FileOrigins.canonicalize`).
    */
  private def checkPolicy(raw: String, env: CliEnv): Either[Int, Path] =
    env.policy.evaluate(env.cwd.resolve(raw), env.cwd) match
      case Right(resolved) => Right(resolved)
      case Left(denial) =>
        env.audit.record(AuditEvent.Denied(denial.path, denial.reason))
        env.err(s"gx: ${denial.reason}")
        Left(ExitCode.InvalidPathOrPolicy)

  /** The file must exist, because the command reads it. */
  private def requireFile(path: Path, env: CliEnv): Either[Int, Path] =
    if Files.isRegularFile(path) then Right(path)
    else
      env.err(if Files.exists(path) then s"gx: $path is not a file" else s"gx: $path does not exist")
      Left(ExitCode.InvalidPathOrPolicy)

  /** A file, its text and its hash as gx read them, and the parsed diagram. */
  private final case class Loaded(path: Path, doc: Document, diagram: DiagramText.Named):
    def graph: ViewerGraph    = diagram.graph
    def names: ElementNames   = diagram.names
    def format: DiagramFormat = diagram.format

  private def load(path: Path, env: CliEnv): Either[Int, Loaded] =
    Documents.read(path) match
      case Left(DocumentError.NotFound(_)) =>
        env.err(s"gx: $path does not exist")
        Left(ExitCode.InvalidPathOrPolicy)
      case Left(DocumentError.NotAFile(_)) =>
        env.err(s"gx: $path is not a file")
        Left(ExitCode.InvalidPathOrPolicy)
      case Left(other) =>
        env.err(s"gx: cannot read $path: ${describe(other)}")
        Left(ExitCode.InvalidPathOrPolicy)
      case Right(doc) =>
        DiagramText.parseNamed(doc.text) match
          case Left(why) =>
            env.err(s"gx: $path: $why")
            Left(ExitCode.InvalidPathOrPolicy)
          case Right(diagram) => Right(Loaded(path, doc, diagram))

  private def loadFile(command: Command, args: Args, env: CliEnv): Either[Int, Loaded] =
    fileArg(command, args, env).flatMap(load(_, env))

  // ---------------------------------------------------- element names

  /** The texts that are not element names at all. A malformed name is a usage
    * error (exit 1).
    */
  private def malformed(texts: Seq[String]): Seq[String] =
    texts.flatMap: text =>
      if text.isEmpty then Some("an element name cannot be empty")
      else ElementName.parse(text).left.toOption

  /** Resolve element names against the diagram. A malformed name is exit 1. A
    * name that names nothing is exit 4. The message reports each bad name, so
    * an agent can correct all of them in one step.
    */
  private def resolve(texts: Seq[String], names: ElementNames, env: CliEnv): Either[Int, Vector[ElementId]] =
    val bad = malformed(texts)
    if bad.nonEmpty then
      env.err(s"gx: ${bad.mkString("; ")}")
      Left(ExitCode.Usage)
    else
      names.resolveAll(texts).left.map: why =>
        env.err(s"gx: $why. 'gx nodes', 'gx arrows' and 'gx groups' print the names.")
        ExitCode.InvalidPathOrPolicy

  private def isGroup(id: ElementId): Boolean = id match
    case _: GroupId => true
    case _          => false

  private def kindOf(id: ElementId): String = id match
    case _: NodeId  => "a node"
    case _: ArrowId => "an arrow"
    case _: GroupId => "a group"

  /** Refuse an element of a kind that the command does not take (exit 4). */
  private def onlyKinds[A](
      command: Command,
      ids:     Vector[ElementId],
      names:   ElementNames,
      takes:   String,
      env:     CliEnv
  )(pick: PartialFunction[ElementId, A]): Either[Int, Vector[A]] =
    ids.find(id => !pick.isDefinedAt(id)) match
      case Some(other) =>
        env.err(s"gx: ${command.name} takes $takes; '${names.render(other)}' is ${kindOf(other)}")
        Left(ExitCode.InvalidPathOrPolicy)
      case None => Right(ids.collect(pick))

  // ------------------------------------------------------------- read

  private def readNodes(command: Command, args: Args, env: CliEnv): Int =
    readOnly(command, args, env): loaded =>
      val rows = loaded.names.nodes.map: (id, name) =>
        (name, ElementRef.render(id), labelOf(loaded.graph.nodes.get(id).map(_.label.toString)))
      if args.json then
        printJson(rows.map((name, ref, label) => ujson.Obj("name" -> name, "ref" -> ref, "label" -> optional(label))), env)
      else columns(rows.map((name, _, label) => name -> label.map(quoted).getOrElse(""))).foreach(env.out)
      ExitCode.Ok

  private def readArrows(command: Command, args: Args, env: CliEnv): Int =
    readOnly(command, args, env): loaded =>
      val op = if loaded.graph.tpe.isDirected then "->" else "--"
      val rows = loaded.names.arrows.flatMap: (id, name) =>
        loaded.graph.arrows.get(id).map: arrow =>
          val label = labelOf(arrow.attributes.values.get(Label.attrId).map(_.toString))
          (name, ElementRef.render(id), loaded.names.render(arrow.source), loaded.names.render(arrow.target), label)
      if args.json then
        printJson(
          rows.map((name, ref, source, target, label) =>
            ujson.Obj("name" -> name, "ref" -> ref, "source" -> source, "target" -> target, "label" -> optional(label))
          ),
          env
        )
      else
        columns(rows.map((name, _, source, target, label) =>
          name -> (s"$source $op $target" + label.map(l => s"  ${quoted(l)}").getOrElse(""))
        )).foreach(env.out)
      ExitCode.Ok

  private def readGroups(command: Command, args: Args, env: CliEnv): Int =
    readOnly(command, args, env): loaded =>
      val rows = loaded.names.groups.map: (id, name) =>
        val label  = labelOf(loaded.graph.groups.get(id).map(_.label.toString))
        val parent = loaded.graph.membership(id).map(loaded.names.render)
        (name, ElementRef.render(id), label, parent)
      if args.json then
        printJson(
          rows.map((name, ref, label, parent) =>
            ujson.Obj("name" -> name, "ref" -> ref, "label" -> optional(label), "parent" -> optional(parent))
          ),
          env
        )
      else
        columns(rows.map((name, _, label, parent) =>
          name -> (label.map(quoted).toVector ++ parent.map(p => s"(in $p)")).mkString("  ")
        )).foreach(env.out)
      ExitCode.Ok

  private def readAttrs(command: Command, args: Args, env: CliEnv): Int =
    readOnly(command, args, env): loaded =>
      val texts = args.positional.drop(1)
      if texts.isEmpty then
        env.err(s"gx: attrs needs at least one <element>; usage: ${command.synopsis}")
        ExitCode.Usage
      else
        resolve(texts, loaded.names, env) match
          case Left(code) => code
          case Right(ids) =>
            // The attributes as the DOT writer prints them: `style=filled`,
            // not the internal parts of a style.
            val printed = loaded.graph.copy(elements = loaded.graph.elements.combineStyleAttributes)
            val items   = ids.map(id => (loaded.names.render(id), ElementRef.render(id), attributesOf(printed, id)))
            if args.json then
              printJson(
                items.map((name, ref, attrs) =>
                  ujson.Obj(
                    "name"       -> name,
                    "ref"        -> ref,
                    "attributes" -> ujson.Obj.from(attrs.map((k, v) => k -> ujson.Str(v)))
                  )
                ),
                env
              )
            else
              for (name, _, attrs) <- items do
                env.out(name)
                attrs.foreach((k, v) => env.out(s"  $k=$v"))
            ExitCode.Ok

  /** A read command: it parses the file, and it never writes anything. */
  private def readOnly(command: Command, args: Args, env: CliEnv)(body: Loaded => Int): Int =
    loadFile(command, args, env).fold(identity, body)

  /** The attributes of an element, as the DOT writer prints them. Thus the
    * list leaves out:
    *
    *   - the internal keys: `_gvid` and the other `_` keys,
    *   - the source name of a group (it is the name, not an attribute),
    *   - an empty value: the reader gives each arrow `label=""` when one arrow
    *     has a label,
    *   - a group attribute that has its default value (`labelloc=t`), except
    *     `cluster`.
    */
  private def attributesOf(graph: ViewerGraph, id: ElementId): Vector[(String, String)] =
    val defaults: Map[String, String] = id match
      case _: GroupId =>
        ViewerGroup.defaultGroupAttributes.values.map((k, v) => k.value -> v.toString).toMap - Cluster.attrId.value
      case _ => Map.empty
    graph.getAttributesById(id).values.toVector
      .map((k, v) => k.value -> v.toString)
      .filterNot: (k, v) =>
        k.startsWith("_") || v.isEmpty || defaults.get(k).contains(v) ||
          (isGroup(id) && k == Name.attrId.value)

  /** `\N` is the DOT default label (the node id), so it is no label. */
  private def labelOf(label: Option[String]): Option[String] =
    label.filter(l => l.nonEmpty && l != "\\N")

  private def optional(value: Option[String]): ujson.Value = value.map(ujson.Str(_)).getOrElse(ujson.Null)

  private def quoted(text: String): String = "\"" + text.replace("\"", "\\\"") + "\""

  private def printJson(items: Vector[ujson.Value], env: CliEnv): Unit =
    env.out(ujson.Arr.from(items).render(indent = 2))

  /** Two columns: the name, then a description. The names line up. */
  private def columns(rows: Vector[(String, String)]): Vector[String] =
    val width = rows.map(_._1.length).maxOption.getOrElse(0)
    rows.map((name, description) =>
      if description.isEmpty then name else name.padTo(width, ' ') + "  " + description
    )

  // ----------------------------------------------------------- change

  /** `name=value` is an attribute; each other argument is an element. The
    * value can contain `=` and newlines.
    */
  private val AttributeArg = "(?s)([A-Za-z_][A-Za-z0-9_]*)=(.*)".r

  private def setAttributes(command: Command, args: Args, env: CliEnv): Int =
    val (attributes, elements) = args.positional.drop(1).partitionMap:
      case AttributeArg(name, value) => Left(name -> value)
      case other                     => Right(other)
    val repeated = attributes.groupBy(_._1).collect { case (name, all) if all.size > 1 => name }.toVector.sorted
    // gx refuses the names that start with `_`. The DOT writer leaves `_gvid`
    // out of the file, so a set of it would change nothing and still exit 0.
    // The writer keeps `id`, so gx accepts it.
    val dropped = attributes.map(_._1).filter(_.startsWith("_")).distinct

    def usage(why: String): Int =
      env.err(s"gx: $why; usage: ${command.synopsis}")
      ExitCode.Usage

    if args.positional.isEmpty then usage("set needs a <file>")
    else if elements.isEmpty then usage("set needs at least one <element> before the attributes")
    else if attributes.isEmpty then usage("set needs at least one <name>=<value> (an empty value removes the attribute)")
    else if repeated.nonEmpty then usage(s"set gives ${repeated.mkString(", ")} more than once; give each attribute once")
    else if dropped.nonEmpty then usage(s"gx cannot write ${dropped.mkString(", ")}: its DOT writer leaves it out of the file")
    else
      change(command, args, env): loaded =>
        resolve(elements, loaded.names, env).flatMap: ids =>
          val targets = ids.toSet
          val steps = attributes.map: (name, value) =>
            if value.isEmpty then SourceCommand.RemoveAttribute(targets, name)
            else SourceCommand.SetAttribute(targets, name, value)
          steps
            .foldLeft[Either[Int, ViewerGraph]](Right(loaded.graph))((graph, step) => graph.flatMap(runSource(_, step, env)))
            .map(_ -> Vector.empty)

  private def reset(command: Command, args: Args, env: CliEnv): Int =
    withElements(command, args, env): (loaded, ids) =>
      runSource(loaded.graph, SourceCommand.ResetAttributes(ids.toSet), env).map(_ -> Vector.empty)

  private def group(command: Command, args: Args, env: CliEnv): Int =
    withElements(command, args, env): (loaded, ids) =>
      onlyKinds(command, ids, loaded.names, "nodes and groups", env) {
        case n: NodeId  => n: ElementId
        case g: GroupId => g: ElementId
      }.flatMap: members =>
        val label = args.value("label").getOrElse("")
        runSource(loaded.graph, SourceCommand.Group(members.toSet, label), env).map: next =>
          // The output names the new group, because the next command needs the
          // name. It is the name that the writer prints.
          val made = (next.groupIds -- loaded.graph.groupIds).headOption
          next -> made.map(id => "group" -> ElementNames(next, loaded.format).render(id)).toVector

  /** `ungroup` takes groups. The members of each group move out to the parent
    * of the group, and the empty group goes away.
    */
  private def ungroup(command: Command, args: Args, env: CliEnv): Int =
    withElements(command, args, env): (loaded, ids) =>
      onlyKinds(command, ids, loaded.names, "groups", env) { case g: GroupId => g }.flatMap: groups =>
        val members: Set[ElementId] = loaded.graph.getDirectChildren(groups.toSet).map(m => m: ElementId)
        runSource(loaded.graph, SourceCommand.Ungroup(members), env).map(_ -> Vector.empty)

  private def combineRecord(command: Command, args: Args, env: CliEnv): Int =
    withElements(command, args, env): (loaded, ids) =>
      onlyKinds(command, ids, loaded.names, "nodes", env) { case n: NodeId => n }.flatMap: nodes =>
        if !loaded.graph.canCombineNodes(nodes.toSet) then
          env.err("gx: combine-record needs two or more nodes, and the nodes must be in the same group")
          Left(ExitCode.InvalidPathOrPolicy)
        else runSource(loaded.graph, SourceCommand.CombineIntoRecord(nodes.toSet), env).map(_ -> Vector.empty)

  private def splitRecord(command: Command, args: Args, env: CliEnv): Int =
    oneRecord(command, args, env)(SourceCommand.SplitRecord(_))

  private def transposeRecord(command: Command, args: Args, env: CliEnv): Int =
    oneRecord(command, args, env)(SourceCommand.TransposeRecord(_))

  private def oneRecord(command: Command, args: Args, env: CliEnv)(make: NodeId => SourceCommand): Int =
    if args.positional.size > 2 then
      env.err(s"gx: ${command.name} takes exactly one <node>; usage: ${command.synopsis}")
      ExitCode.Usage
    else
      withElements(command, args, env): (loaded, ids) =>
        onlyKinds(command, ids, loaded.names, "a node", env) { case n: NodeId => n }.flatMap:
          case Vector(node) if loaded.graph.isRecordNode(node) =>
            runSource(loaded.graph, make(node), env).map(_ -> Vector.empty)
          case Vector(node) =>
            env.err(s"gx: '${loaded.names.render(node)}' is not a record node: its shape is not record or Mrecord")
            Left(ExitCode.InvalidPathOrPolicy)
          case several =>
            env.err(s"gx: ${command.name} takes one node, and the name gives ${several.size}")
            Left(ExitCode.Usage)

  /** A change command whose arguments after the file are element names. */
  private def withElements(command: Command, args: Args, env: CliEnv)(
      edit: (Loaded, Vector[ElementId]) => Either[Int, (ViewerGraph, Vector[(String, String)])]
  ): Int =
    if args.positional.sizeIs == 1 then
      env.err(s"gx: ${command.name} needs at least one element; usage: ${command.synopsis}")
      ExitCode.Usage
    else
      change(command, args, env): loaded =>
        resolve(args.positional.drop(1), loaded.names, env).flatMap(ids => edit(loaded, ids))

  private def runSource(graph: ViewerGraph, command: SourceCommand, env: CliEnv): Either[Int, ViewerGraph] =
    SourceCommands.run(graph, command) match
      case Right(result) =>
        result.updatedGraph.toRight:
          env.err(s"gx: unexpected failure: ${command.name} gave no graph")
          ExitCode.Unknown
      case Left(error) =>
        env.err(s"gx: ${error.message}")
        Left(ExitCode.InvalidPathOrPolicy)

  /** The steps of each change (plan §2):
    *
    *   1. Read the file and its hash, and parse it.
    *   2. Make the change on the graph.
    *   3. Render the text. If it is the same text, write nothing.
    *   4. Refuse a write that removes content that the graph does not keep
    *      (comments, `strict`), unless the caller gives `--reformat`.
    *   5. Write the file only if it still has the hash of step 1. Else stop
    *      with exit 5, and the file stays as the other writer left it.
    *
    * `edit` gives the new graph, and the extra fields for the output.
    */
  private def change(command: Command, args: Args, env: CliEnv)(
      edit: Loaded => Either[Int, (ViewerGraph, Vector[(String, String)])]
  ): Int =
    loadFile(command, args, env).flatMap(loaded => edit(loaded).map(loaded -> _)) match
      case Left(code) => code
      case Right((loaded, (graph, extra))) =>
        val text = DiagramText.render(graph, loaded.format)
        val lost = RoundTripLoss.scan(loaded.doc.text, loaded.format)
        if text == loaded.doc.text then reportWrite(loaded.path, loaded.doc.hash, changed = false, extra, args, env)
        else if lost.nonEmpty && !args.has("reformat") then
          env.err(
            s"gx: gx writes the whole file again, and that removes ${listed(lost)} from ${loaded.path}. " +
              "Add --reformat to accept the rewrite, or change the text yourself."
          )
          ExitCode.InvalidPathOrPolicy
        else
          env.beforeWrite(loaded.path)
          Documents.write(loaded.path, text, loaded.doc.hash) match
            case Left(DocumentError.Conflict(_, expected, actual)) =>
              env.audit.record(AuditEvent.Conflict(loaded.path.toString, expected, actual, "cli"))
              env.err(
                s"gx: ${loaded.path} changed after gx read it, so gx did not write it " +
                  s"(expected ${expected.hex.take(12)}, found ${actual.hex.take(12)}). Run the command again."
              )
              ExitCode.Conflict
            case Left(err) =>
              env.err(s"gx: cannot write ${loaded.path}: ${describe(err)}")
              ExitCode.InvalidPathOrPolicy
            case Right(doc) =>
              env.audit.record(AuditEvent.Written(loaded.path.toString, doc.hash, "cli"))
              reportWrite(loaded.path, doc.hash, changed = true, extra, args, env)

  private def reportWrite(
      path:    Path,
      hash:    ContentHash,
      changed: Boolean,
      extra:   Vector[(String, String)],
      args:    Args,
      env:     CliEnv
  ): Int =
    if args.json then
      val fields = Vector[(String, ujson.Value)]("path" -> path.toString, "hash" -> hash.hex, "changed" -> changed)
      env.out(ujson.Obj.from(fields ++ extra.map((k, v) => k -> ujson.Str(v))).render(indent = 2))
    else
      val notes = Option.unless(changed)("unchanged").toVector ++ extra.map((k, v) => s"$k $v")
      env.out((Vector(path.toString, hash.hex) ++ notes).mkString("  "))
    ExitCode.Ok

  /** "the comments and the `strict` keyword", in the order of the enum. */
  private def listed(lost: Set[UnmodeledContent]): String =
    UnmodeledContent.values.toList.filter(lost.contains).map(_.label) match
      case init :+ last if init.nonEmpty => s"${init.mkString(", ")} and $last"
      case names                         => names.mkString

  // ---------------------------------------------------------- metadata

  /** The entry of a file. An entry file that the store cannot read is an
    * error, because a write would replace it.
    */
  private def entryOf(path: Path, env: CliEnv): Either[Int, Option[LibraryEntry]] =
    env.library.get(path.toString) match
      case Right(entry)                    => Right(Some(entry))
      case Left(MetadataError.NotFound(_)) => Right(None)
      case Left(other) =>
        env.err(s"gx: the library entry of $path cannot be read ($other). 'gx remove $path' removes it.")
        Left(ExitCode.Unknown)

  /** Change the metadata of a file.
    *
    * With no entry, `create` decides. A command that adds metadata makes the
    * entry, and that puts the diagram in the library. A command that removes
    * metadata does nothing, and gives `None`. Only a file that exists can get a
    * new entry.
    */
  private def updateEntry(path: Path, create: Boolean, env: CliEnv)(
      update: DiagramMetadata => DiagramMetadata
  ): Either[Int, Option[LibraryEntry]] =
    entryOf(path, env).flatMap:
      case Some(entry) =>
        val next = update(entry.metadata)
        if next == entry.metadata then Right(Some(entry))
        else put(entry.copy(metadata = next, updatedAt = env.now()), env).map(Some(_))
      case None if !create => Right(None)
      case None =>
        requireFile(path, env).flatMap: _ =>
          val now  = env.now()
          val meta = update(DiagramMetadata.empty)
          put(LibraryEntry(path.toString, meta, now, now), env).map(Some(_))

  private def put(entry: LibraryEntry, env: CliEnv): Either[Int, LibraryEntry] =
    env.library.put(entry).left.map: err =>
      env.err(s"gx: cannot write the library entry of ${entry.path}: $err")
      ExitCode.Unknown

  /** The names of the elements of the file, when the file parses. */
  private def namesOf(path: Path): Option[ElementNames] =
    Documents.read(path).toOption.flatMap(doc => DiagramText.parseNamed(doc.text).toOption).map(_.names)

  /** A stored ref, as a name when the file parses, else as the ref.
    * `ElementNames.render` gives the ref spelling for an element that the file
    * does not have.
    */
  private def displayed(refs: Iterable[String], names: Option[ElementNames]): Vector[String] =
    refs.toVector.map(ref =>
      (names, ElementRef.parse(ref).toOption) match
        case (Some(n), Some(id)) => n.render(id)
        case _                   => ref
    ).sorted

  private def infoJson(path: Path, entry: Option[LibraryEntry], names: Option[ElementNames]): ujson.Obj =
    val meta = entry.map(_.metadata)
    ujson.Obj(
      "path"             -> path.toString,
      "inLibrary"        -> entry.isDefined,
      "tags"             -> ujson.Arr.from(meta.toVector.flatMap(_.tags).map(ujson.Str(_))),
      "notes"            -> meta.map(_.notes).getOrElse(""),
      "hidden"           -> ujson.Arr.from(displayed(meta.toVector.flatMap(_.hiddenElements), names).map(ujson.Str(_))),
      "collapsed"        -> ujson.Arr.from(displayed(meta.toVector.flatMap(_.collapsedGroups), names).map(ujson.Str(_))),
      "autoDetectFormat" -> meta.flatMap(_.autoDetectFormat).map(ujson.Bool(_)).getOrElse(ujson.Null)
    )

  /** A value of `info`, for a person. */
  private def text(value: ujson.Value): String = value match
    case ujson.Arr(items) => if items.isEmpty then "(none)" else items.map(_.str).mkString(", ")
    case ujson.Str(s)     => if s.isEmpty then "(none)" else s
    case other            => if other.isNull then "(not set)" else other.render()

  /** The output of a metadata command: the metadata of the file with
    * `--json`, else one line with the field that the command changes.
    */
  private def reportEntry(
      path:  Path,
      entry: Option[LibraryEntry],
      names: Option[ElementNames],
      field: String,
      args:  Args,
      env:   CliEnv
  ): Int =
    if args.json then env.out(infoJson(path, entry, names).render(indent = 2))
    else if entry.isEmpty then env.out(s"$path  not in the library; nothing to change")
    else env.out(s"$path  $field: ${text(infoJson(path, entry, names)(field)).linesIterator.mkString(" / ")}")
    ExitCode.Ok

  private def hide(command: Command, args: Args, env: CliEnv): Int =
    addElements(command, args, env, "hidden"): (_, ids) =>
      Right(MetadataCommand.Hide(ids.toSet))

  private def collapse(command: Command, args: Args, env: CliEnv): Int =
    addElements(command, args, env, "collapsed"): (loaded, ids) =>
      onlyKinds(command, ids, loaded.names, "groups", env) { case g: GroupId => g }
        .map(groups => MetadataCommand.Collapse(groups.toSet))

  /** `hide` and `collapse`: resolve the names against the file, then store
    * the elements in the ref spelling. The file must parse.
    */
  private def addElements(command: Command, args: Args, env: CliEnv, field: String)(
      make: (Loaded, Vector[ElementId]) => Either[Int, MetadataCommand]
  ): Int =
    val texts = args.positional.drop(1)
    loadFile(command, args, env) match
      case Left(code) => code
      case Right(_) if texts.isEmpty =>
        env.err(s"gx: ${command.name} needs at least one element; usage: ${command.synopsis}")
        ExitCode.Usage
      case Right(loaded) =>
        val result =
          for
            ids   <- resolve(texts, loaded.names, env)
            meta  <- make(loaded, ids)
            entry <- updateEntry(loaded.path, create = true, env)(MetadataCommands.run(_, meta))
          yield entry
        result.fold(identity, entry => reportEntry(loaded.path, entry, Some(loaded.names), field, args, env))

  private def unhide(command: Command, args: Args, env: CliEnv): Int =
    removeElements(command, args, env, "hidden", MetadataCommand.UnhideAll)(_.hiddenElements): (_, ids) =>
      Right(MetadataCommand.Unhide(ids.toSet))

  private def expand(command: Command, args: Args, env: CliEnv): Int =
    removeElements(command, args, env, "collapsed", MetadataCommand.ExpandAll)(_.collapsedGroups): (names, ids) =>
      ids.find(!isGroup(_)) match
        case Some(other) =>
          val name = names.fold(ElementRef.render(other))(_.render(other))
          env.err(s"gx: expand takes groups; '$name' is ${kindOf(other)}")
          Left(ExitCode.InvalidPathOrPolicy)
        case None => Right(MetadataCommand.Expand(ids.collect { case g: GroupId => g }.toSet))

  /** `unhide` and `expand`: the elements, or `--all`.
    *
    * A name resolves against the file when the file parses. An element that
    * the file no longer has can still go out of the metadata by its ref
    * spelling, for example `node:old`.
    */
  private def removeElements(command: Command, args: Args, env: CliEnv, field: String, all: MetadataCommand)(
      stored: DiagramMetadata => Set[String]
  )(make: (Option[ElementNames], Vector[ElementId]) => Either[Int, MetadataCommand]): Int =
    val texts = args.positional.drop(1)
    def usage(why: String): Int =
      env.err(s"gx: ${command.name} $why; usage: ${command.synopsis}")
      ExitCode.Usage
    fileArg(command, args, env) match
      case Left(code)                                     => code
      case Right(_) if texts.isEmpty && !args.has("all")  => usage("needs elements or --all")
      case Right(_) if texts.nonEmpty && args.has("all")  => usage("takes elements or --all, not both")
      case Right(path) =>
        entryOf(path, env) match
          case Left(code)  => code
          case Right(None) => reportEntry(path, None, None, field, args, env)
          case Right(Some(entry)) =>
            val names = namesOf(path)
            val metaCommand =
              if args.has("all") then Right(all)
              else resolveStored(texts, names, stored(entry.metadata), path, env).flatMap(make(names, _))
            metaCommand
              .flatMap(c => updateEntry(path, create = false, env)(MetadataCommands.run(_, c)))
              .fold(identity, e => reportEntry(path, e, names, field, args, env))

  /** Resolve names for a removal. The file decides when it parses. A ref
    * spelling that is in the metadata also works.
    */
  private def resolveStored(
      texts:  Seq[String],
      names:  Option[ElementNames],
      stored: Set[String],
      path:   Path,
      env:    CliEnv
  ): Either[Int, Vector[ElementId]] =
    val bad = malformed(texts)
    if bad.nonEmpty then
      env.err(s"gx: ${bad.mkString("; ")}")
      Left(ExitCode.Usage)
    else
      val results = texts.map: text =>
        val storedRef = ElementRef.parse(text).toOption.filter(id => stored.contains(ElementRef.render(id)))
        names match
          case Some(n) => n.resolve(text).left.flatMap(why => storedRef.map(Vector(_)).toRight(why))
          case None =>
            storedRef.map(Vector(_)).toRight:
              s"gx cannot parse $path, so it cannot resolve '$text'. Give the ref spelling (for example node:$text), or --all"
      val (failed, found) = results.partitionMap(identity)
      if failed.nonEmpty then
        env.err(s"gx: ${failed.mkString("; ")}")
        Left(ExitCode.InvalidPathOrPolicy)
      else Right(found.flatten.distinct.toVector)

  private def tag(command: Command, args: Args, env: CliEnv): Int =
    withTags(command, args, env): (path, tags) =>
      updateEntry(path, create = true, env)(MetadataCommands.run(_, MetadataCommand.Tag(tags)))

  private def untag(command: Command, args: Args, env: CliEnv): Int =
    withTags(command, args, env): (path, tags) =>
      updateEntry(path, create = false, env)(MetadataCommands.run(_, MetadataCommand.Untag(tags)))

  private def withTags(command: Command, args: Args, env: CliEnv)(
      update: (Path, List[String]) => Either[Int, Option[LibraryEntry]]
  ): Int =
    val tags = args.positional.drop(1).toList
    fileArg(command, args, env) match
      case Left(code) => code
      case Right(_) if tags.isEmpty =>
        env.err(s"gx: ${command.name} needs at least one <tag>; usage: ${command.synopsis}")
        ExitCode.Usage
      case Right(_) if tags.exists(_.trim.isEmpty) =>
        env.err("gx: a tag cannot be empty")
        ExitCode.Usage
      case Right(path) =>
        update(path, tags).fold(identity, entry => reportEntry(path, entry, None, "tags", args, env))

  private def note(command: Command, args: Args, env: CliEnv): Int =
    val texts   = args.positional.drop(1)
    val sources = texts.size + Seq("stdin", "clear").count(args.has)
    fileArg(command, args, env) match
      case Left(code) => code
      case Right(_) if texts.size > 1 =>
        env.err(s"gx: note takes one <text>; put the text in quotes; usage: ${command.synopsis}")
        ExitCode.Usage
      case Right(_) if sources != 1 =>
        env.err(s"gx: note needs exactly one of <text>, --stdin and --clear; usage: ${command.synopsis}")
        ExitCode.Usage
      case Right(_) if texts.headOption.exists(_.isEmpty) =>
        env.err("gx: the text of the note is empty; --clear removes the note")
        ExitCode.Usage
      case Right(path) =>
        // Text from stdin loses its last line endings, as `$(...)` does in a shell.
        val note =
          if args.has("clear") then ""
          else if args.has("stdin") then env.stdin().reverse.dropWhile(c => c == '\n' || c == '\r').reverse
          else texts.head
        updateEntry(path, create = note.nonEmpty, env)(MetadataCommands.run(_, MetadataCommand.SetNotes(note)))
          .fold(identity, entry => reportEntry(path, entry, None, "notes", args, env))

  private def info(command: Command, args: Args, env: CliEnv): Int =
    fileArg(command, args, env).flatMap(path => entryOf(path, env).map(path -> _)) match
      case Left(code) => code
      case Right((path, None)) if !Files.exists(path) =>
        env.err(s"gx: $path does not exist, and it is not in the library")
        ExitCode.InvalidPathOrPolicy
      case Right((path, entry)) =>
        val json = infoJson(path, entry, namesOf(path))
        if args.json then env.out(json.render(indent = 2))
        else
          val width = json.obj.keys.map(_.length).max + 1
          for (key, value) <- json.obj do
            val lines = text(value).linesIterator.toVector
            env.out(s"${(key + ":").padTo(width, ' ')} ${lines.mkString("\n" + " " * (width + 1))}")
        ExitCode.Ok

  private def ls(command: Command, args: Args, env: CliEnv): Int =
    if args.positional.nonEmpty then
      env.err(s"gx: ls takes no file; usage: ${command.synopsis}")
      ExitCode.Usage
    else
      val (entries, unreadable) = env.library.listing()
      for bad <- unreadable do
        env.err(s"gx: warning: the library entry ${bad.file} cannot be read (${bad.message}). It is not in the list.")
      val rows = entries
        .filter(e => args.value("tag").forall(e.metadata.tags.contains))
        .map(e => e -> !Files.exists(Paths.get(e.path)))
      if args.json then
        printJson(
          rows.map((e, missing) =>
            ujson.Obj("path" -> e.path, "tags" -> ujson.Arr.from(e.metadata.tags.map(ujson.Str(_))), "missing" -> missing)
          ),
          env
        )
      else if rows.isEmpty then env.out("(no diagrams)")
      else
        for (e, missing) <- rows do
          val tags = Option.when(e.metadata.tags.nonEmpty)(s"tags: ${e.metadata.tags.mkString(", ")}")
          env.out((Vector(e.path) ++ tags ++ Option.when(missing)("missing")).mkString("  "))
      ExitCode.Ok

  private def add(command: Command, args: Args, env: CliEnv): Int =
    // Each file is checked before the first write, so a bad file adds nothing.
    val checked = files(command, args, env).flatMap(paths =>
      paths.foldLeft[Either[Int, Vector[Path]]](Right(Vector.empty))((acc, p) => acc.flatMap(ok => requireFile(p, env).map(ok :+ _)))
    )
    val results = checked.flatMap: paths =>
      paths.foldLeft[Either[Int, Vector[(Path, Boolean)]]](Right(Vector.empty)): (acc, path) =>
        for
          done  <- acc
          entry <- entryOf(path, env)
          added <- if entry.isDefined then Right(false) else updateEntry(path, create = true, env)(identity).map(_ => true)
        yield
          // V-08: the audit log records each path that gx lets into the library.
          if added then env.audit.record(AuditEvent.Allowed(path.toString, "add"))
          done :+ (path -> added)
    results.fold(
      identity,
      rows =>
        if args.json then printJson(rows.map((p, added) => ujson.Obj("path" -> p.toString, "added" -> added)), env)
        else for (p, added) <- rows do env.out(s"$p  ${if added then "added" else "already in the library"}")
        ExitCode.Ok
    )

  private def remove(command: Command, args: Args, env: CliEnv): Int =
    val results = files(command, args, env).flatMap: paths =>
      paths.foldLeft[Either[Int, Vector[(Path, Boolean)]]](Right(Vector.empty)): (acc, path) =>
        acc.flatMap: done =>
          env.library.remove(path.toString) match
            case Right(removed) => Right(done :+ (path -> removed))
            case Left(err) =>
              env.err(s"gx: cannot remove the library entry of $path: $err")
              Left(ExitCode.Unknown)
    results.fold(
      identity,
      rows =>
        if args.json then printJson(rows.map((p, removed) => ujson.Obj("path" -> p.toString, "removed" -> removed)), env)
        else
          for (p, removed) <- rows do
            env.out(s"$p  ${if removed then "removed from the library; the file stays" else "not in the library; nothing to change"}")
        ExitCode.Ok
    )

  /** Each positional argument is a file. Each goes through the policy. */
  private def files(command: Command, args: Args, env: CliEnv): Either[Int, Vector[Path]] =
    if args.positional.isEmpty then
      env.err(s"gx: ${command.name} needs at least one <file>; usage: ${command.synopsis}")
      Left(ExitCode.Usage)
    else
      args.positional
        .foldLeft[Either[Int, Vector[Path]]](Right(Vector.empty))((acc, raw) => acc.flatMap(done => checkPolicy(raw, env).map(done :+ _)))
        .map(_.distinct)

  // ----------------------------------------------------------- desktop

  /** `open` sends `show` with a FILE target: the desktop watches the file and
    * raises its window.
    */
  private def open(command: Command, args: Args, env: CliEnv): Int =
    fileArg(command, args, env).flatMap(requireFile(_, env)) match
      case Left(code) => code
      case Right(path) =>
        val payload = ujson.Obj("target" -> ujson.Obj("kind" -> "file", "path" -> path.toString))
        desktop("show", payload, env, refused = "the desktop refused to open it"): result =>
          val focused = result.objOpt.flatMap(_.get("focused")).flatMap(_.boolOpt).getOrElse(true)
          if args.json then env.out(ujson.Obj("path" -> path.toString, "focused" -> focused).render(indent = 2))
          else
            env.out(s"showing $path")
            // A desktop can run with no window on the screen. Say so, because
            // the person cannot see a success.
            if !focused then env.err("gx: the desktop is running but has no window to raise")
          ExitCode.Ok

  /** `select` resolves the names against the file, and sends the refs and
    * the canonical path of the file.
    *
    * The page compares the path with the diagram on the screen (plan §2). For
    * a different diagram, it refuses with `VIEW_REJECTED`, and the message
    * names the diagram on the screen.
    */
  private def select(command: Command, args: Args, env: CliEnv): Int =
    val texts = args.positional.drop(1)
    loadFile(command, args, env) match
      case Left(code) => code
      case Right(_) if texts.isEmpty =>
        env.err(s"gx: select needs at least one element; usage: ${command.synopsis}")
        ExitCode.Usage
      case Right(loaded) =>
        resolve(texts, loaded.names, env) match
          case Left(code) => code
          case Right(ids) =>
            val frame = LiveViewCodec.encode(LiveViewCommand.Select(loaded.path.toString, ids.toSet))
            liveView(frame, env, Some(loaded.path)): _ =>
              val names = ids.map(loaded.names.render)
              if args.json then
                env.out(
                  ujson.Obj(
                    "path"     -> loaded.path.toString,
                    "selected" -> ujson.Arr.from(ids.map(ElementRef.render).sorted.map(ujson.Str(_))),
                    "names"    -> ujson.Arr.from(names.map(ujson.Str(_)))
                  ).render(indent = 2)
                )
              else env.out(s"${loaded.path}  selected: ${names.mkString(", ")}")
              ExitCode.Ok

  /** `selection` asks the desktop what is selected, and in which diagram.
    *
    * The page answers with the path of the file on the screen and the refs
    * (`LiveViewSelection`). gx renders the names against that file, so an
    * agent can give them to the next command. When the screen shows no file
    * (an example), or gx cannot read the file, the selection stays in the ref
    * spelling, and stderr says why.
    */
  private def selection(command: Command, args: Args, env: CliEnv): Int =
    if args.positional.nonEmpty then
      env.err(s"gx: selection takes no file; usage: ${command.synopsis}")
      ExitCode.Usage
    else
      liveView(LiveViewCodec.encode(LiveViewCommand.WhatIsSelected), env, None): result =>
        LiveViewSelection.decode(result) match
          case Left(why) =>
            env.err(s"gx: the desktop gave an answer that this gx cannot read ($why).")
            env.err(s"gx: use the same version of Graph Explorer Desktop and gx (this gx is ${buildinfo.BuildInfo.version})")
            ExitCode.Unknown
          case Right(answer) =>
            val names = answer.path match
              case None =>
                env.err(s"gx: the desktop shows ${answer.shows}, and it is not a file. The selection is in the ref spelling.")
                None
              case Some(path) => selectionNames(path, env)
            val shown = answer.selection.map: ref =>
              (names, ElementRef.parse(ref).toOption) match
                case (Some(n), Some(id)) => n.render(id)
                case _                   => ref
            if args.json then
              env.out(
                ujson.Obj(
                  "path"      -> answer.path.fold(ujson.Null)(ujson.Str(_)),
                  "selection" -> ujson.Arr.from(shown.map(ujson.Str(_)))
                ).render(indent = 2)
              )
            else
              val what = if shown.isEmpty then "nothing selected" else s"selected: ${shown.mkString(", ")}"
              env.out(answer.path.fold(what)(path => s"$path  $what"))
            ExitCode.Ok

  /** The element names of the file on the screen. `None`, and a line on
    * stderr, when the policy refuses the path, or the file cannot be read or
    * parsed. Then the selection stays in the ref spelling.
    */
  private def selectionNames(path: String, env: CliEnv): Option[ElementNames] =
    env.policy.evaluate(Paths.get(path), env.cwd) match
      case Left(denial) =>
        env.audit.record(AuditEvent.Denied(denial.path, denial.reason))
        env.err(s"gx: gx may not read $path (${denial.reason}). The selection is in the ref spelling.")
        None
      case Right(allowed) =>
        Documents.read(allowed).left.map(describe).flatMap(doc => DiagramText.parseNamed(doc.text)) match
          case Right(diagram) => Some(diagram.names)
          case Left(why) =>
            env.err(s"gx: gx cannot read $path ($why). The selection is in the ref spelling.")
            None

  private def fit(command: Command, args: Args, env: CliEnv): Int =
    fileArg(command, args, env).flatMap(requireFile(_, env)) match
      case Left(code) => code
      case Right(path) =>
        liveView(LiveViewCodec.encode(LiveViewCommand.ResetView(path.toString)), env, Some(path)): _ =>
          if args.json then env.out(ujson.Obj("path" -> path.toString).render(indent = 2))
          else env.out(s"$path  fit to the window")
          ExitCode.Ok

  /** A live view command. `file` is the diagram that the command names. When
    * the desktop shows a different diagram, the page refuses with
    * `VIEW_REJECTED`, and gx tells the caller to show the file first.
    */
  private def liveView(frame: ujson.Obj, env: CliEnv, file: Option[Path])(onOk: ujson.Value => Int): Int =
    file match
      case Some(path) =>
        env.rpc("live-view", frame) match
          case Left(ChannelError.Rpc("VIEW_REJECTED", message, _)) =>
            env.err(s"gx: the desktop refused it (VIEW_REJECTED): $message")
            env.err(s"gx: show the file first: gx open ${shellWord(path.toString)}, then retry")
            ExitCode.InvalidPathOrPolicy
          case other => outcome(other, env, refused = "the desktop refused it")(onOk)
      case None => desktop("live-view", frame, env, refused = "the desktop refused it")(onOk)

  /** A path as one word for a shell: as it is when it has only safe
    * characters, and else in single quotes.
    */
  private def shellWord(text: String): String =
    if text.nonEmpty && text.forall(c => c.isLetterOrDigit || "/._-+:@%=,".contains(c)) then text
    else "'" + text.replace("'", "'\\''") + "'"

  /** One request to the desktop, and the exit code of each failure. */
  private def desktop(method: String, params: ujson.Obj, env: CliEnv, refused: String)(
      onOk: ujson.Value => Int
  ): Int =
    outcome(env.rpc(method, params), env, refused)(onOk)

  private def outcome(answer: Either[ChannelError, ujson.Value], env: CliEnv, refused: String)(
      onOk: ujson.Value => Int
  ): Int =
    answer match
      case Right(result) => onOk(result)

      case Left(ChannelError.NoDesktop(detail)) =>
        env.err("gx: no desktop is running. Start Graph Explorer Desktop, then retry.")
        env.err("gx: (every other gx command works without it)")
        env.err(s"gx: ($detail)")
        ExitCode.NeedsDesktop

      // A desktop that is up but has no window. Nothing is wrong with the
      // request, so this is NeedsDesktop and not a refusal.
      case Left(ChannelError.Rpc("NO_WINDOW", message, _)) =>
        env.err(s"gx: the desktop is running but has no window to show it in ($message)")
        env.err("gx: open the Graph Explorer window, then retry")
        ExitCode.NeedsDesktop

      // A desktop with no diagram on the screen. The next step is the same as
      // with no desktop: show the diagram.
      case Left(ChannelError.Rpc("NO_LIVE_VIEW", message, _)) =>
        env.err(s"gx: $message")
        env.err("gx: show the diagram first: gx open <file>")
        ExitCode.NeedsDesktop

      case Left(ChannelError.Rpc(code, message, _)) =>
        env.err(s"gx: $refused ($code): $message")
        ExitCode.InvalidPathOrPolicy

      case Left(ChannelError.Io(message)) =>
        env.err(s"gx: control channel error: $message")
        ExitCode.Unknown

  // ------------------------------------------------------------- status

  /** It exits 0 also with no desktop. A missing desktop stops only the
    * desktop commands, so it is information and not a failure.
    */
  private def status(command: Command, args: Args, env: CliEnv): Int =
    if args.positional.nonEmpty then
      env.err(s"gx: status takes no file; usage: ${command.synopsis}")
      ExitCode.Usage
    else
      val entries = env.library.list()
      val missing = entries.count(e => !Files.exists(Paths.get(e.path)))

      val desktop    = env.rpc("status", ujson.Obj()).toOption.flatMap(_.objOpt)
      val watchItems = desktop.flatMap(_.get("watches")).flatMap(_.arrOpt).toVector.flatten
      val watchPaths = watchItems.flatMap(_.objOpt.flatMap(_.get("path")).flatMap(_.strOpt)).sorted

      // The socket answers from the start of the process, before the window.
      // Thus "the call succeeded" does not mean "there is a window". With no
      // `state`, the desktop is older than the field. It answered only when it
      // was fully up, so it is running.
      val starting = desktop.flatMap(_.get("state")).flatMap(_.strOpt).contains("starting")
      val running  = desktop.isDefined && !starting

      if args.json then
        env.out(
          ujson.Obj(
            "ok"                -> true,
            "library"           -> env.library.root.toString,
            "diagrams"          -> entries.size,
            "missing"           -> missing,
            "desktopRunning"    -> running,
            "desktopStarting"   -> starting,
            "desktopWatches"    -> watchItems.size,
            "desktopWatchPaths" -> ujson.Arr.from(watchPaths.map(ujson.Str(_)))
          ).render(indent = 2)
        )
      else
        env.out(s"library:  ${env.library.root}")
        env.out(s"diagrams: ${entries.size}" + (if missing > 0 then s" ($missing missing)" else ""))
        // NOT "open": this is the WATCH registry of the desktop. A watched
        // file need not be on the screen.
        if running then env.out(s"desktop:  running (${watching(watchItems.size)})")
        else if starting then env.out("desktop:  starting (its window is not up yet)")
        else env.out("desktop:  not running (only open, select, selection and fit need it)")
      ExitCode.Ok

  private def watching(n: Int): String =
    if n == 0 then "watching nothing"
    else if n == 1 then "watching 1 file"
    else s"watching $n files"

  // -------------------------------------------------------------- watch

  /** Stream the changes of files to stdout, one line for each event. It needs
    * no desktop, so a script or an agent can pipe it. It stops only when the
    * process stops.
    */
  private def watch(command: Command, args: Args, env: CliEnv): Int =
    val interval = args.value("interval") match
      case None      => Right(50L)
      case Some(raw) => raw.toLongOption.filter(_ >= 0).toRight(raw)
    (files(command, args, env), interval) match
      case (Left(code), _) => code
      case (_, Left(raw)) =>
        env.err(s"gx: --interval needs a number of milliseconds, 0 or more, not '$raw'; usage: ${command.synopsis}")
        ExitCode.Usage
      case (Right(paths), Right(ms)) =>
        val origins  = paths.map(FileOrigins.originOf(_, env.cwd)).distinct
        val registry = WatchRegistry(env.audit, debounceMs = ms)
        origins.foreach(registry.watch)
        for o <- origins do env.err(s"watching ${o.value}")
        while env.keepWatching() do
          for event <- registry.poll() do emitWatchEvent(event, args, env)
          env.sleep(ms)
        ExitCode.Ok

  private def emitWatchEvent(event: WatchEvent, args: Args, env: CliEnv): Unit =
    val (kind, uri, hash) = event match
      case WatchEvent.Changed(u, h)  => ("changed", u, h)
      case WatchEvent.Restored(u, h) => ("restored", u, h)
      case WatchEvent.Deleted(u, h)  => ("deleted", u, h)
    if args.json then env.out(ujson.Obj("event" -> kind, "origin" -> uri.value, "hash" -> hash.hex).render())
    else env.out(s"$kind\t${uri.value}")

  // -------------------------------------------------------------- skill

  /** Print where the agent skill is, and the sentence to give an agent.
    *
    * It does NOT install the skill. The skill is a prompt that a coding agent
    * loads and acts on, so a write into an agent directory is a decision of
    * the person. Each harness also keeps skills in a different place.
    *
    * The location is pinned to this binary by default. The skill names
    * commands, flags and exit codes, and they change between releases.
    */
  private def skill(command: Command, args: Args, env: CliEnv): Int =
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
          // Addressed to the agent, so that the block can go into a prompt as
          // it is. The URL has its own line, so that it survives a copy out of
          // a terminal that wraps the lines.
          env.out("  Read and analyze the skill at")
          env.out(s"    ${found.raw}")
          env.out(s"  together with ${SkillLocation.SupportingFiles.mkString(" and ")} beside it, which it links.")
          env.out("  Check it against the `gx` on this machine, then install the files as a")
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

  // ------------------------------------------------------------ helpers

  private def describe(err: DocumentError): String = err match
    case DocumentError.NotFound(p)       => s"no such file: $p"
    case DocumentError.NotAFile(p)       => s"not a regular file: $p"
    case DocumentError.AlreadyExists(p)  => s"already exists: $p"
    case DocumentError.Io(p, m)          => s"$p: $m"
    case DocumentError.Conflict(p, _, _) => s"conflict on $p"
