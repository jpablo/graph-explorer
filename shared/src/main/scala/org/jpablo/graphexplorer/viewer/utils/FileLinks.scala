package org.jpablo.graphexplorer.viewer.utils

/** The links of a diagram to the files next to it, and the image paths relative to it.
  *
  * A drop of a file on the desktop gives a node with a link to the file (the DOT attribute `URL`). A command-click on
  * the node opens the file. The link has one of two forms:
  *
  *   - A path relative to the folder of the diagram, when the file is in that folder or below it. Thus the folder can
  *     move as one unit, and the link stays correct. The path is as the disk has it, with no escapes, as the path of an
  *     image (`image="arch.assets/img-3f2a.png"`).
  *   - Else an absolute `file://` URL, with the escapes of a URL. A relative path with `../` breaks when the diagram
  *     moves to a different folder, and an absolute path is not a URL.
  *
  * The shell resolves a link again before it opens it (`local_files::link_target` in `main.rs`), with the same rule
  * for a relative path ([[isRelativePath]]).
  */
object FileLinks:

  private val Scheme = "^[A-Za-z][A-Za-z0-9+.-]*:".r

  /** True for a link with a URL scheme: `https:`, `file:`, `data:`, `asset:`, also `C:`. */
  def hasScheme(link: String): Boolean = Scheme.findPrefixOf(link).isDefined

  /** The folder of the file at `path`. Both separators count, because the path comes from the shell of any platform. */
  def dirOf(path: String): String =
    val i = math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    if i > 0 then path.substring(0, i) else if i == 0 then path.substring(0, 1) else ""

  /** The path of `relative` in the folder of the diagram at `diagramPath`. */
  def resolve(diagramPath: String, relative: String): String =
    val dir = dirOf(diagramPath)
    if dir.endsWith("/") || dir.endsWith("\\") then dir + relative else s"$dir/$relative"

  /** The link from the diagram at `diagramPath` to the file at the absolute path `filePath`. With no diagram file,
    * the link is a `file://` URL.
    */
  def linkFor(diagramPath: Option[String], filePath: String): String =
    diagramPath
      .map(dirOf)
      .filter(_.nonEmpty)
      .flatMap: dir =>
        val prefix = if dir.endsWith("/") || dir.endsWith("\\") then dir else dir + "/"
        Option.when(filePath.startsWith(prefix))(filePath.drop(prefix.length))
      .filter(isRelativePath)
      .getOrElse(fileUrl(filePath))

  /** The `file://` URL of the absolute path `path`. Each byte that is not an ASCII letter, a digit, `-._~` or `/` is
    * an escape (`%20` for a space). A Windows path gets the form `file:///C:/...`.
    */
  def fileUrl(path: String): String =
    val slashed = path.replace('\\', '/')
    val rooted  = if slashed.startsWith("/") then slashed else "/" + slashed
    val drive   = """^/[A-Za-z]:(/|$)""".r.findPrefixOf(rooted).isDefined
    val encoded = StringBuilder()
    rooted.getBytes("UTF-8").zipWithIndex.foreach: (b, i) =>
      val c = (b & 0xff).toChar
      val plain = (c < 128 && c.isLetterOrDigit) || "-._~/".contains(c) || (drive && i == 2 && c == ':')
      if plain then encoded += c else encoded ++= f"%%${b & 0xff}%02X"
    s"file://$encoded"

  /** True for a path relative to the folder of a diagram: not empty, no URL scheme, not absolute, and no character
    * that a person cannot see. It does not start with `#` or `?` (a part of a page), `~` (a home folder that the shell
    * does not expand) or `-` (it looks like an option of a command). `is_relative_link` in `local_files.rs` has the
    * same rule.
    */
  def isRelativePath(src: String): Boolean =
    src.nonEmpty &&
      !hasScheme(src) &&
      !"/\\~#?-".contains(src.head) &&
      !src.exists(_.isControl)

  /** True for a link to a local file: a `file://` URL, or a path relative to the diagram. Only the desktop can open
    * such a link.
    */
  def isLocalFileLink(link: String): Boolean =
    (link.toLowerCase.startsWith("file://") && !link.exists(_.isControl)) || isRelativePath(link)
