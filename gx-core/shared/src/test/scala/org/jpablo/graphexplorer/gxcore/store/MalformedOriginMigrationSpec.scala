package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.DiagramMetadata

import scala.util.Try

/** An old record whose origin is not a well-formed URI must not stop the
  * migration.
  *
  * The plan reads the path of each origin with `OriginUri.filePath`. That
  * function threw on a `%` that does not start an escape. The executor makes
  * the plan outside its `try`, so the exception left the migration. Then
  * `metadata/` did not come into existence, and `gx` failed again on each
  * later library command, because `Cli.migrate` runs before each one.
  */
class MalformedOriginMigrationSpec extends FunSuite:

  private val record = LegacyRecord(
    id = "r1",
    name = "Budget",
    folder = FolderPath.root,
    format = "DOT",
    text = "digraph G { a -> b }\n",
    origin = Some("file:///tmp/50% done.dot"),
    metadata = DiagramMetadata(),
    createdAt = 1000L,
    updatedAt = 2000L
  )

  private val old = OldRecords.fromFiles(Vector("r1.json" -> Right(OldRecordJson.of(record))))

  test("the desktop can list the files of a record whose origin has a bare '%'"):
    Try(old.filePaths).fold(e => fail(s"OldRecords.filePaths threw $e"), _ => ())

  test("a record whose origin has a bare '%' does not stop the migration"):
    val plan = Try(
      MetadataMigration.plan(
        old = old,
        readFile = _ => None, // the file is missing
        diagramsFolder = "/home/me/Documents/Graph Explorer",
        existingNames = Set.empty,
        now = 3000L
      )
    ).fold(e => fail(s"the plan threw $e"), identity)
    // The file is missing, so the text of the record goes into a new file.
    assertEquals(plan.files.map(_.text), Vector(record.text))
    assertEquals(plan.entries.size, 1)
