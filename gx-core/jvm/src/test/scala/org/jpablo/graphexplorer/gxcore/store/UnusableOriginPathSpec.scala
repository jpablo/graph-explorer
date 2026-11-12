package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.fs.{Audit, GxHome, WatchRegistry}
import org.jpablo.graphexplorer.gxcore.model.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** An origin whose path no local file can have must not stop the migration,
  * and it must not stop `gx watch`.
  *
  * `OriginUri.filePath` decodes `%00` to the character U+0000. `Paths.get`
  * refuses that character on each platform, and on Windows it also refuses
  * `<`, `>`, `|`, `"`, `?` and `*`. It throws `InvalidPathException`. The
  * executor calls `Paths.get` in its `localPath` callback, and the plan runs
  * outside the `try` of the executor. Thus one such old record stops the
  * migration. `metadata/` then does not come into existence, and each later
  * library command fails again, because `Cli.migrate` runs before each one.
  *
  * The `%` fix of d8b88a37 (`MalformedOriginMigrationSpec`) made the decoder
  * total. The `Paths.get` after the decoder is the second half of this bug.
  */
class UnusableOriginPathSpec extends FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("gx-core-unusable-origin").toRealPath(),
    teardown = dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private val NulOrigin = "file:///tmp/a%00b.dot"

  private val record = LegacyRecord(
    id = "r1",
    name = "Budget",
    folder = FolderPath.root,
    format = "DOT",
    text = "digraph G { a -> b }\n",
    origin = Some(NulOrigin),
    metadata = DiagramMetadata(tags = List("money")),
    createdAt = 10L,
    updatedAt = 20L
  )

  tmp.test("an origin with %00 does not stop the migration"): dir =>
    val home = dir.resolve("home")
    val docs = dir.resolve("docs")
    val old  = GxHome.libraryDir(home).resolve("diagrams")
    Files.createDirectories(old)
    Files.writeString(old.resolve("r1.json"), OldRecordJson.of(record))

    val result = Try(MetadataMigrationExecutor.run(home, docs, now = () => 99L))
      .fold(e => fail(s"the migration threw $e"), identity)

    result match
      case MetadataMigrationResult.Done(_) => ()
      case other                           => fail(s"expected Done, got $other")

    // No local file can have the path of the origin, so the text of the
    // record goes into a new file, as for a missing file.
    val written = Files.walk(docs).iterator.asScala.filter(Files.isRegularFile(_)).toVector
    assertEquals(written.map(Files.readString(_, StandardCharsets.UTF_8)), Vector(record.text))

    val entries = MetadataStore(GxHome.metadataDir(home)).list()
    assertEquals(entries.size, 1)
    assertEquals(entries.head.metadata.tags.headOption, Some("money"))

  tmp.test("gx watch of an origin with %00 does not throw"): dir =>
    val registry = WatchRegistry(Audit(dir.resolve("audit.jsonl")))
    val uri      = OriginUri.parse(NulOrigin).fold(fail(_), identity)
    Try(registry.watch(uri)).fold(e => fail(s"watch threw $e"), identity)
    assertEquals(registry.watched, Vector(uri))
    // No local file can have this path, so the origin is absent and the poll
    // reports nothing.
    assertEquals(Try(registry.poll()).fold(e => fail(s"poll threw $e"), identity), Vector.empty)
