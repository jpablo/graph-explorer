package org.jpablo.graphexplorer.gxcore.fs

import munit.FunSuite

import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, CountDownLatch, Executors, TimeUnit}
import scala.jdk.CollectionConverters.*

/** `Documents.create` makes a new diagram file. The migration uses it for
  * each old record that has no file, in the folder of the person.
  *
  * Two defects:
  *
  *   - `AtomicFiles` writes through `Files.createTempFile`, which makes the
  *     file `rw-------`. It copies the permissions only from a file that
  *     exists. Thus each NEW file was owner-only, and not what the umask of
  *     the person gives.
  *   - `create` checks `Files.exists`, and then it moves the temp file with
  *     `REPLACE_EXISTING`. A file that a different writer makes between the
  *     check and the move is replaced. The migration says that it never
  *     writes over a file that exists.
  */
class DocumentsCreateSpec extends FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("gx-core-create").toRealPath(),
    teardown = dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private def posix(dir: Path): Boolean =
    Files.getFileAttributeView(dir, classOf[PosixFileAttributeView]) != null

  tmp.test("a created file has the permissions of a plain new file") { dir =>
    assume(posix(dir), "no POSIX permissions on this file system")
    val plain    = Files.writeString(dir.resolve("plain.txt"), "x")
    val expected = Files.getPosixFilePermissions(plain).asScala.toSet
    val path     = dir.resolve("new.dot")
    assert(Documents.create(path, "digraph G { a }\n").isRight)
    assertEquals(
      Files.getPosixFilePermissions(path).asScala.toSet,
      expected,
      "a new diagram file must get the permissions that the umask gives, as a plain new file does"
    )
  }

  tmp.test("two creates of one path at the same time: one wins, and the other changes nothing") { dir =>
    val rounds = 20
    val pool   = Executors.newFixedThreadPool(2)
    try
      val outcomes = (1 to rounds).map: i =>
        val path  = dir.resolve(s"race-$i.dot")
        val start = CountDownLatch(1)
        val tasks = Vector("A", "B").map: who =>
          pool.submit(new Callable[(String, Either[DocumentError, Document])]:
            def call() =
              start.await()
              who -> Documents.create(path, s"digraph $who { a }\n")
          )
        start.countDown()
        path -> tasks.map(_.get(10, TimeUnit.SECONDS))

      val bothWon = outcomes.filter((_, results) => results.count(_._2.isRight) != 1)
      assert(
        bothWon.isEmpty,
        s"in ${bothWon.size} of $rounds rounds, both creates of one path succeeded, so one replaced the other"
      )
      outcomes.foreach: (path, results) =>
        val (winner, _) = results.find(_._2.isRight).get
        assertEquals(Files.readString(path), s"digraph $winner { a }\n", s"$path does not hold the text of the winner")
        results.filter(_._2.isLeft).foreach: (_, loss) =>
          assert(
            loss.left.exists(_.isInstanceOf[DocumentError.AlreadyExists]),
            s"the create that lost must say AlreadyExists, not $loss"
          )

      val leftovers = Files.list(dir).iterator.asScala.map(_.getFileName.toString).filterNot(_.startsWith("race-")).toVector
      assertEquals(leftovers, Vector.empty, "a lost create must not leave a temp file")
    finally pool.shutdownNow()
  }
