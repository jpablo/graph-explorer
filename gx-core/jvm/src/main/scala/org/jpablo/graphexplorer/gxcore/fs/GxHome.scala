package org.jpablo.graphexplorer.gxcore.fs

import java.nio.file.{Path, Paths}

/** Where Graph Explorer keeps its own state: `library/` and `runtime/`.
  *
  * `$GX_HOME` replaces that directory wholesale. Default: `~/.graph-explorer`.
  *
  * This exists because the two halves could not be pointed at the same place.
  * The desktop resolves its home through `dirs::home_dir()`, which follows
  * `$HOME`; `gx` is a GraalVM native image and reads `user.home`, which on macOS
  * comes from the password database and ignores `$HOME` entirely. So a test that
  * launched a desktop under a redirected `$HOME` and then asked `gx` about it
  * was silently asking about the REAL desktop — which is exactly what happened
  * while verifying v0.9.4, and the only tell was a sandbox library reporting six
  * diagrams and a watched file it could not possibly have.
  *
  * One variable both sides read closes that. It is a testing affordance first,
  * but it is equally the answer to "keep a second library" or "run against a
  * throwaway one", neither of which had any answer before.
  *
  * NOT a general home override. The user's real home still decides what the
  * access policy denies (`~/.ssh`, `~/.gnupg`, ...): relocating gx's data must
  * never quietly un-deny a secret, so those keep reading the true home.
  */
object GxHome:

  val EnvVar = "GX_HOME"

  private val DefaultDirName = ".graph-explorer"

  private val SysEnv: String => Option[String] = k => sys.env.get(k)
  private val SysHome: () => Path              = () => Paths.get(sys.props.getOrElse("user.home", "."))

  /** Resolved once, at the edge of the process, and threaded from there.
    *
    * Both parameters are injected rather than read here so this is a pure
    * function of its inputs — the env and the JVM's idea of home are exactly
    * the two things a test cannot set without affecting the whole process.
    */
  def resolve(
      env:      String => Option[String] = SysEnv,
      userHome: () => Path               = SysHome
  ): Either[String, Path] =
    absoluteDir(env, EnvVar, userHome().resolve(DefaultDirName), "they would use different libraries")

  /** `$GX_HOME/library`, the directory `gx` and the desktop agree on. */
  def libraryDir(gxHome: Path): Path = gxHome.resolve("library")

  /** `$GX_HOME/runtime`: the control file, the socket, the audit log. */
  def runtimeDir(gxHome: Path): Path = gxHome.resolve("runtime")

  /** `$GX_HOME/library/metadata`: one JSON file for each diagram (plan §3).
    *
    * It is beside `diagrams/`, the old records. The migration makes it, and
    * it runs only while this directory does not exist.
    */
  def metadataDir(gxHome: Path): Path = libraryDir(gxHome).resolve("metadata")

  val DiagramsDirEnvVar = "GX_DIAGRAMS_DIR"

  /** The folder for new diagrams: `$GX_DIAGRAMS_DIR`, or else
    * `~/Documents/Graph Explorer`.
    *
    * A new diagram is a new file (ADR 0001), and the migration writes the old
    * records that have no file into this folder. `$GX_HOME` does not move it,
    * because the diagrams are the files of the person and not the data of
    * gx. Thus a test that sets `GX_HOME` must also set `GX_DIAGRAMS_DIR`.
    *
    * A relative value is refused for the reason that a relative `GX_HOME` is
    * refused: gx and the desktop do not share a working directory.
    */
  def diagramsDir(
      env:      String => Option[String] = SysEnv,
      userHome: () => Path               = SysHome
  ): Either[String, Path] =
    absoluteDir(
      env,
      DiagramsDirEnvVar,
      userHome().resolve("Documents").resolve("Graph Explorer"),
      "they would put new diagrams in different folders"
    )

  /** The value of `key`, or else `default`. An empty value counts as no value.
    *
    * A RELATIVE value is refused rather than absolutised, and that is the
    * whole point of the variable rather than a technicality.
    *
    * Absolutising resolves against the reading process's working directory,
    * and the two halves do not share one: `gx` runs from the user's shell, a
    * GUI-launched desktop from wherever the launcher put it (often `/`). So
    * `GX_HOME=./scratch` would name two different directories and each half
    * would be internally consistent about the wrong one — silently, which is
    * exactly the failure this variable exists to prevent (see the note above
    * about v0.9.4).
    *
    * There is no value in guessing which cwd was meant, so it is refused
    * before either process advertises a socket or scans a library.
    *
    * @param consequence what goes wrong with a relative value, for the error
    */
  private def absoluteDir(
      env:         String => Option[String],
      key:         String,
      default:     => Path,
      consequence: String
  ): Either[String, Path] =
    env(key).map(_.trim).filter(_.nonEmpty) match
      case None => Right(default)
      case Some(dir) =>
        val path = Paths.get(dir)
        if path.isAbsolute then Right(path.normalize())
        else
          Left(
            s"$key must be an absolute path, but is '$dir'. " +
              "A relative value resolves against each process's working directory, and gx and " +
              s"the desktop do not share one — $consequence without saying so."
          )
