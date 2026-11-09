package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.fs.{FileOrigins, GxHome}
import org.jpablo.graphexplorer.gxcore.model.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** The JVM half of the migration: it reads the old records, makes the plan,
  * and performs it. Each directory is a temporary one. No test touches the
  * real library or the real Documents folder.
  */
class MetadataMigrationExecutorSpec extends FunSuite:

  /** `home/` is GX_HOME, `docs/` is the folder of new diagrams, `work/` has
    * the files of the person.
    */
  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("gx-core-migration").toRealPath(),
    teardown = dir => Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private def home(dir: Path) = dir.resolve("home")
  private def docs(dir: Path) = dir.resolve("docs")
  private def work(dir: Path) = Files.createDirectories(dir.resolve("work"))

  private def metadataDir(dir: Path) = GxHome.metadataDir(home(dir))
  private def diagramsDir(dir: Path) = GxHome.libraryDir(home(dir)).resolve("diagrams")

  private def record(id: String, name: String, text: String, file: Option[Path] = None): LegacyRecord =
    LegacyRecord(
      id = id,
      format = "DOT",
      text = text,
      origin = file.map(f => FileOrigins.originOf(f).value),
      metadata = DiagramMetadata(name = name, hiddenElements = Set("node:a")),
      createdAt = 10L,
      updatedAt = 20L
    )

  /** Write old records into `diagrams/`, as the old `gx` did. */
  private def save(dir: Path, records: LegacyRecord*): Unit =
    Files.createDirectories(diagramsDir(dir))
    records.foreach(r => Files.writeString(diagramsDir(dir).resolve(s"${r.id}.json"), OldRecordJson.of(r)))

  private def run(dir: Path, beforePublish: Path => Unit = _ => ()): MetadataMigrationResult =
    MetadataMigrationExecutor.run(home(dir), docs(dir), now = () => 99L, beforePublish = beforePublish)

  private def done(result: MetadataMigrationResult): Vector[String] = result match
    case MetadataMigrationResult.Done(report) => report
    case other                                => fail(s"expected Done, got $other")

  /** Each file under a directory, with its bytes, so that a test can say
    * "nothing changed" about all of it.
    */
  private def snapshot(root: Path): Map[String, Vector[Byte]] =
    if !Files.exists(root) then Map.empty
    else
      Files.walk(root).iterator.asScala
        .filter(Files.isRegularFile(_))
        .map(p => root.relativize(p).toString -> Files.readAllBytes(p).toVector)
        .toMap

  private def read(p: Path): String = Files.readString(p, StandardCharsets.UTF_8)

  // ------------------------------------------------ the rules, end to end

  tmp.test("with no old records, the migration makes an empty metadata/"): dir =>
    assertEquals(done(run(dir)), Vector.empty)
    assert(Files.isDirectory(metadataDir(dir)))
    assertEquals(Files.list(metadataDir(dir)).count(), 0L)
    assert(!Files.exists(docs(dir)), "the migration should make no folder of diagrams when it has nothing to write")

  tmp.test("each kind of record goes to its place"): dir =>
    val inSync    = work(dir).resolve("sync.dot")
    val diverged  = work(dir).resolve("diverged.dot")
    val gone      = work(dir).resolve("gone.dot")
    Files.writeString(inSync, "digraph { a }\n")
    Files.writeString(diverged, "digraph { file }\n")
    save(
      dir,
      record("sync", "sync", "digraph { a }\n", Some(inSync)),
      record("div", "Diverged", "digraph { record }\n", Some(diverged)),
      record("gone", "Gone", "digraph { gone }\n", Some(gone)),
      record("free", "Free", "digraph { free }\n")
    )

    val report = done(run(dir))

    val folder = FileOrigins.canonicalize(docs(dir), dir)
    assertEquals(read(folder.resolve("Recovered/Diverged.dot")), "digraph { record }\n")
    assertEquals(read(folder.resolve("Gone.dot")), "digraph { gone }\n")
    assertEquals(read(folder.resolve("Free.dot")), "digraph { free }\n")
    assertEquals(read(diverged), "digraph { file }\n", "the file of the person must not change")

    val entries = MetadataStore(metadataDir(dir)).list()
    assertEquals(
      entries.map(_.path).toSet,
      Set(
        inSync.toString,
        diverged.toString,
        folder.resolve("Recovered/Diverged.dot").toString,
        folder.resolve("Gone.dot").toString,
        folder.resolve("Free.dot").toString
      )
    )
    assert(entries.forall(_.metadata.hiddenElements == Set("node:a")), entries)
    assert(entries.forall(_.createdAt == 10L), entries)
    assert(entries.forall(_.updatedAt == 99L), entries)
    assert(report.exists(_.contains("Recovered")), report)
    assert(report.exists(_.contains(gone.toString)), report)

  tmp.test("the migration never overwrites a file that exists"): dir =>
    val folder = Files.createDirectories(docs(dir))
    Files.writeString(folder.resolve("Notes.dot"), "digraph { mine }\n")
    save(dir, record("n", "Notes", "digraph { library }\n"))

    done(run(dir))

    assertEquals(read(folder.resolve("Notes.dot")), "digraph { mine }\n")
    assertEquals(read(folder.resolve("Notes 2.dot")), "digraph { library }\n")

  tmp.test("the old directory stays unchanged"): dir =>
    val bound = work(dir).resolve("b.dot")
    Files.writeString(bound, "digraph { file }\n")
    save(dir, record("b", "B", "digraph { record }\n", Some(bound)), record("f", "F", "digraph { f }\n"))
    Files.writeString(diagramsDir(dir).resolve("broken.json"), "{ not json")
    val before = snapshot(GxHome.libraryDir(home(dir)))

    done(run(dir))

    val after = snapshot(GxHome.libraryDir(home(dir))).filterNot((k, _) => k.startsWith("metadata"))
    assertEquals(after, before)

  tmp.test("a second run does nothing"): dir =>
    save(dir, record("n", "Notes", "digraph { n }\n"))
    done(run(dir))
    val docsBefore     = snapshot(docs(dir))
    val metadataBefore = snapshot(metadataDir(dir))

    // A new old record after the first run must not reach the new store:
    // the migration runs one time.
    save(dir, record("late", "Late", "digraph { late }\n"))
    assertEquals(run(dir), MetadataMigrationResult.AlreadyDone)

    assertEquals(snapshot(docs(dir)), docsBefore)
    assertEquals(snapshot(metadataDir(dir)), metadataBefore)

  tmp.test("an old record that does not parse is reported, and the others move"): dir =>
    save(dir, record("n", "Notes", "digraph { n }\n"))
    Files.writeString(diagramsDir(dir).resolve("broken.json"), "{ not json")

    val report = done(run(dir))

    assertEquals(MetadataStore(metadataDir(dir)).list().size, 1)
    assert(report.exists(_.contains("broken")), report)

  // ------------------------------------------------ the atomic rename

  tmp.test("metadata/ does not exist until the end, and then it has each entry"): dir =>
    save(dir, record("a", "A", "digraph { a }\n"), record("b", "B", "digraph { b }\n"))
    var seen = false

    done(run(dir, beforePublish = temp =>
      seen = true
      assert(!Files.exists(metadataDir(dir)), "metadata/ exists before the rename")
      assertEquals(temp.getParent, metadataDir(dir).getParent, "the temporary directory must be beside metadata/")
      assertEquals(MetadataStore(temp).list().size, 2)
    ))

    assert(seen)
    assertEquals(MetadataStore(metadataDir(dir)).list().size, 2)
    assertEquals(
      Files.list(GxHome.libraryDir(home(dir))).iterator.asScala.map(_.getFileName.toString).toSet,
      Set("diagrams", "metadata"),
      "the temporary directory must be gone"
    )

  tmp.test("a failure before the rename leaves no metadata/, and the next run finishes without copies"): dir =>
    save(dir, record("a", "A", "digraph { a }\n"))

    run(dir, beforePublish = _ => throw RuntimeException("crash")) match
      case MetadataMigrationResult.Failed(reason, _) => assert(reason.contains("crash"), reason)
      case other                                     => fail(s"expected Failed, got $other")
    assert(!Files.exists(metadataDir(dir)))
    assertEquals(
      Files.list(GxHome.libraryDir(home(dir))).iterator.asScala.map(_.getFileName.toString).toSet,
      Set("diagrams"),
      "the temporary directory must be gone"
    )
    val folder = FileOrigins.canonicalize(docs(dir), dir)
    assertEquals(read(folder.resolve("A.dot")), "digraph { a }\n", "the file of the first run stays")

    done(run(dir))
    assertEquals(Files.list(folder).iterator.asScala.map(_.getFileName.toString).toSet, Set("A.dot"))
    assertEquals(MetadataStore(metadataDir(dir)).list().map(_.path), Vector(folder.resolve("A.dot").toString))

  tmp.test("execute refuses a planned file that exists, and changes nothing"): dir =>
    val target = Files.createDirectories(docs(dir)).resolve("A.dot")
    Files.writeString(target, "digraph { mine }\n")
    val plan = MetadataMigrationPlan(
      files = Vector(PlannedFile(target.toString, "digraph { other }\n")),
      entries = Vector(LibraryEntry(target.toString, DiagramMetadata(name = "A"), 1L, 2L)),
      report = Vector.empty
    )

    MetadataMigrationExecutor.execute(plan, metadataDir(dir)) match
      case MetadataMigrationResult.Failed(reason, _) => assert(reason.contains(target.toString), reason)
      case other                                     => fail(s"expected Failed, got $other")
    assertEquals(read(target), "digraph { mine }\n")
    assert(!Files.exists(metadataDir(dir)))
