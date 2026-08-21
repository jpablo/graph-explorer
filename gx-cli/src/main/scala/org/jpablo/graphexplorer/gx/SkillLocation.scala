package org.jpablo.graphexplorer.gx

/** Where the agent skill that teaches a coding agent to drive `gx` lives.
  *
  * `gx` deliberately does not *install* it. A skill is a prompt that will be
  * loaded into someone's agent and acted on, and a CLI writing one into
  * `~/.claude/skills` on their behalf is the kind of thing that should be a
  * decision rather than a side effect of running a command. Printing a location
  * plus the sentence to hand the agent keeps the human in the loop and works
  * for every harness, including the ones that keep skills somewhere else
  * entirely.
  *
  * The location is PINNED to the running binary. The skill documents command
  * names, param shapes and exit codes, all of which are API that moves between
  * releases — an agent reading the tip of the branch while driving a
  * six-months-old `gx` would be reading about commands it does not have.
  */
object SkillLocation:

  val Repo          = "https://github.com/jpablo/graph-explorer"
  val RawHost       = "https://raw.githubusercontent.com/jpablo/graph-explorer"
  val Directory     = ".claude/skills/gx"
  val File          = s"$Directory/SKILL.md"
  val Name          = "gx"
  val DefaultBranch = "viewer"

  /** A released version, with or without the `v` the tags carry. */
  private val Release = raw"v?(\d+\.\d+\.\d+)".r

  /** The release a dev build was cut from, if its version says.
    *
    * dynver stamps `0.9.3+3-468f2c52`, whose leading `0.9.3` is a real tag and
    * therefore the most useful thing to suggest pinning to — better than naming
    * a version in the help text, which would rot at the next release.
    */
  def baseRelease(version: String): Option[String] =
    Release.findPrefixMatchOf(version).map(_.group(1))

  /** What a resolution came out as.
    *
    * `pinned` is not decoration: an unpinned answer is the branch tip, which
    * may describe commands the running binary does not have, and the caller has
    * to be able to say so.
    */
  final case class Resolved(version: String, ref: String, pinned: Boolean):
    def page: String = s"$Repo/tree/$ref/$Directory"
    def raw:  String = s"$RawHost/$ref/$File"

  /** @param requested
    *   a version named on the command line, if any
    * @param latest
    *   `--latest`: the branch tip, whatever this binary is
    * @param running
    *   this binary's own version (`BuildInfo.version`)
    */
  def resolve(requested: Option[String], latest: Boolean, running: String): Either[String, Resolved] =
    (requested, latest) match
      case (Some(v), true) =>
        Left(s"--latest and an explicit version ('$v') ask for different things; pick one")

      case (Some(Release(v)), false) => Right(Resolved(v, s"v$v", pinned = true))

      case (Some(other), false) =>
        Left(s"'$other' is not a version; expected something like 0.9.4")

      case (None, true) => Right(Resolved(running, DefaultBranch, pinned = false))

      case (None, false) =>
        running match
          // dynver stamps a dev build `0.9.3+13-2b8d0a46+20260730-2334`, and
          // there is no tag by that name to point at. The tip is the honest
          // answer, and the caller says out loud that it is not pinned.
          case Release(v) if v == running => Right(Resolved(v, s"v$v", pinned = true))
          case _                          => Right(Resolved(running, DefaultBranch, pinned = false))
