package org.jpablo.graphexplorer.gxcore.fs

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Directory listings that always close their stream. */
private[gxcore] object Dirs:

  /** The entries of `dir`, in the order of their file names. The result is
    * empty when `dir` is not a directory.
    */
  def entries(dir: Path): Vector[Path] =
    if !Files.isDirectory(dir) then Vector.empty
    else Using.resource(Files.list(dir))(_.iterator.asScala.toVector).sortBy(_.getFileName.toString)

  /** `root` and each file and directory below it. A directory comes before
    * its contents.
    */
  def tree(root: Path): Vector[Path] =
    Using.resource(Files.walk(root))(_.iterator.asScala.toVector)
