package org.jpablo.graphexplorer.gxcore.fs

import java.nio.file.{Path, Paths}

/** Why a path was refused. */
final case class PolicyDenial(path: String, reason: String)

/** Which paths `gx` and the desktop will touch.
  *
  * **This is a guardrail, not a security boundary**, and D6 says so deliberately
  * rather than by omission. §2 of the architecture doc establishes that the
  * principals bound by this policy — `gx` and the desktop — already run as the
  * user and could call `open()` directly. A policy check on them is advisory:
  * the caller asked politely and could have not asked.
  *
  * What it is genuinely good for is catching a mistyped path or a runaway agent,
  * and that is worth having. What it must not do is masquerade as protection
  * against an attacker, which is why the allowlist defaults to empty (allow-all)
  * rather than pretending a stricter default buys safety it cannot buy.
  *
  * The denylist is the part that earns its keep, and it is on by default.
  */
final case class AccessPolicy(allowedRoots: List[Path], deniedRoots: List[Path]):

  /** Evaluate against the FULLY RESOLVED path (V-07).
    *
    * Resolution before evaluation is the entire substance of this check. A
    * symlink inside an allowed root pointing at `~/.ssh` is an allowed path
    * naming a denied file; checking the path as written would permit it. v1 got
    * this right only incidentally — `fs::canonicalize` happened to run first —
    * which the brief flagged as "safe direction, but incidental rather than
    * specified or tested". Specified and tested here.
    */
  def evaluate(path: Path, cwd: Path = Paths.get("").toAbsolutePath): Either[PolicyDenial, Path] =
    val resolved = FileOrigins.canonicalize(path, cwd)

    // Denial wins over permission: a denied root inside an allowed root is still
    // denied, which is the only ordering that makes the denylist meaningful.
    deniedRoots.find(resolved.startsWith) match
      case Some(denied) =>
        Left(PolicyDenial(resolved.toString, s"path is under a denied root: $denied"))
      case None =>
        if allowedRoots.isEmpty then Right(resolved) // allow-all, by decision (D6)
        else if allowedRoots.exists(resolved.startsWith) then Right(resolved)
        else
          Left(
            PolicyDenial(
              resolved.toString,
              s"path is outside every allowed root: ${allowedRoots.mkString(", ")}"
            )
          )

object AccessPolicy:

  /** Sensitive locations refused unless the user overrides them.
    *
    * Not a claim to completeness — a determined caller bypasses all of it by not
    * asking (§2). It is here to stop an agent that was told "read my config"
    * from wandering into credentials.
    */
  def defaultDeniedRoots(home: Path = Paths.get(sys.props.getOrElse("user.home", "/"))): List[Path] =
    List(".ssh", ".gnupg", ".aws", ".kube").map(home.resolve) ++
      List("/System", "/proc", "/sys").map(Paths.get(_))

  val permissive: AccessPolicy =
    AccessPolicy(allowedRoots = Nil, deniedRoots = defaultDeniedRoots())

  /** Read the policy from the environment, matching v1's variables so an
    * existing setup keeps working.
    */
  def fromEnv(env: String => Option[String] = k => sys.env.get(k)): AccessPolicy =
    def roots(keys: String*): List[Path] =
      keys.iterator
        .flatMap(env(_))
        .flatMap(_.split(java.io.File.pathSeparatorChar).iterator)
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(Paths.get(_).toAbsolutePath.normalize())
        .toList

    AccessPolicy(
      allowedRoots = roots("GX_ALLOWED_ROOTS", "GRAPH_EXPLORER_ALLOWED_ROOTS"),
      deniedRoots = defaultDeniedRoots() ++ roots("GX_DENY_ROOTS", "GRAPH_EXPLORER_DENY_ROOTS")
    )
