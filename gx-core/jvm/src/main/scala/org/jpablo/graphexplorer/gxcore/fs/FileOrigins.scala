package org.jpablo.graphexplorer.gxcore.fs

import org.jpablo.graphexplorer.gxcore.model.OriginUri

import java.nio.file.{Files, Path, Paths}
import scala.annotation.tailrec
import scala.util.control.NonFatal

/** Turning a path a human typed into the canonical identity of a file.
  *
  * This is the only supported way to build a `file:` [[OriginUri]], because the
  * URI is the join key between the library, the watch registry and the CLI: two
  * spellings of one file MUST collapse to one value here, or the library grows a
  * second record for the same file and the two fight over it in `Sync` mode.
  *
  * The spellings that have to collapse are not hypothetical. P0 measured them:
  * macOS and Windows both report case-INSENSITIVE filesystems and Linux does
  * not, so `/Users/x/A.dot` and `/Users/x/a.dot` are one file on two platforms
  * of three. `toRealPath` is what recovers the filesystem's own answer, rather
  * than us guessing at case-folding rules per platform.
  */
object FileOrigins:

  /** Resolve a path to its canonical form: absolute, `..` collapsed, symlinks
    * followed, and — on a case-insensitive filesystem — spelled the way the disk
    * spells it.
    *
    * `cwd` is explicit rather than read from the process, because the two
    * processes involved have different ones and only one of them is meaningful.
    * v1 learned this the hard way: `gx` resolves against the user's shell, while
    * the desktop's working directory is an artifact of how it was launched
    * (`gx/src/main.rs:233-247`).
    */
  def canonicalize(path: Path, cwd: Path): Path =
    val absolute = if path.isAbsolute then path else cwd.resolve(path)
    try absolute.toRealPath()
    catch
      case NonFatal(_) =>
        // The file does not exist yet — creating one, or watching a path a
        // generator has not written. Canonicalize the deepest ancestor that DOES
        // exist and re-attach the rest, so a not-yet-created file still gets a
        // stable identity.
        //
        // v1 instead fell back to the un-canonicalized path
        // (`fs::canonicalize(...).unwrap_or(absolute)`), which meant policy
        // checks could run against a path containing `..` or an unresolved
        // symlink. The brief called that "incidental rather than specified";
        // this is the specified version.
        val normalized = absolute.normalize()
        deepestExisting(normalized) match
          case None => normalized
          case Some(existing) =>
            try existing.toRealPath().resolve(existing.relativize(normalized))
            catch case NonFatal(_) => normalized

  @tailrec
  private def deepestExisting(path: Path): Option[Path] =
    Option(path) match
      case None => None
      case Some(p) if Files.exists(p) => Some(p)
      case Some(p) =>
        Option(p.getParent) match
          case None         => None
          case Some(parent) => deepestExisting(parent)

  /** The canonical origin of a local file. */
  def originOf(path: Path, cwd: Path = Paths.get("").toAbsolutePath): OriginUri =
    OriginUri.fromCanonicalPath(canonicalize(path, cwd).toString)

  /** As [[originOf]], for a path given as text. */
  def originOfPath(path: String, cwd: Path = Paths.get("").toAbsolutePath): OriginUri =
    originOf(Paths.get(path), cwd)
