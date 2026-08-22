package org.jpablo.graphexplorer.gxcore.model

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.fs.{Documents, Hashing, LineEnding}

import java.nio.file.{Files, Path}

/** The bridge between the pure reconciler and a real file.
  *
  * `Reconciler` predicts `local` — the record's text hashed as it WOULD be
  * stored — without writing anything. A caller that cannot write, such as the
  * desktop's page, has to trust that prediction. This asserts it is exact
  * against the real writer, on both line-ending conventions.
  */
class ReconcilerWriteSpec extends FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("gx-core-reconcile"),
    teardown = dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private def binding(origin: Path, mode: SyncMode, base: ContentHash) =
    Binding(
      OriginUri.parse(origin.toUri.toString).fold(e => fail(s"bad origin: $e"), identity),
      mode,
      base,
      lastSyncAt = 0L
    )

  private def planFor(origin: Path, mode: SyncMode, base: ContentHash, text: String) =
    val doc = Documents.read(origin).fold(e => fail(s"$e"), identity)
    Reconciler.plan(
      Some(binding(origin, mode, base)),
      text,
      Some(OriginSnapshot(doc.text, doc.hash, doc.lineEnding)),
      Hashing.ofText
    )

  for lineEnding <- List(LineEnding.Lf, LineEnding.Crlf) do
    tmp.test(s"a predicted push base equals what the write produces ($lineEnding)") { dir =>
      val file     = dir.resolve("origin.dot")
      val original = "digraph G {\na -> b\n}"
      val agreed   = Documents.create(file, original, lineEnding).fold(e => fail(s"$e"), identity)

      val edited = "digraph G {\na -> b\nb -> c\n}"
      val plan   = planFor(file, SyncMode.Push, agreed.hash, edited)

      val predicted = plan match
        case ReconcilePlan.Bound(SyncState.Ahead, ReconcileAction.WriteOrigin(text, expecting), local) =>
          assertEquals(text, edited)
          assertEquals(expecting, agreed.hash, "the swap must compare against the AGREED base")
          local
        case other => fail(s"expected a push, got $other")

      // Perform the write the plan asked for, and compare what actually landed.
      val written = Documents.write(file, edited, agreed.hash).fold(e => fail(s"$e"), identity)

      assertEquals(
        written.hash,
        predicted,
        "the reconciler's `local` must equal the hash of the file the write produced"
      )
      assertEquals(
        written.lineEnding,
        lineEnding,
        "a push must not change the file's convention (V-04)"
      )
    }

  tmp.test("after the predicted push lands, the same inputs report InSync") { dir =>
    // The loop closes: a caller that stores `local` as the new base gets a
    // clean state on the next run. If the prediction were off by a byte, this
    // would report Ahead forever — the CRLF bug this phase moved the rule to
    // prevent.
    val file   = dir.resolve("origin.dot")
    val agreed = Documents.create(file, "digraph G {\na\n}", LineEnding.Crlf).fold(e => fail(s"$e"), identity)
    val edited = "digraph G {\na -> b\n}"

    val predicted = planFor(file, SyncMode.Push, agreed.hash, edited) match
      case ReconcilePlan.Bound(_, _, local) => local
      case other                            => fail(s"expected a bound plan, got $other")

    Documents.write(file, edited, agreed.hash).fold(e => fail(s"$e"), identity)

    val after = planFor(file, SyncMode.Push, predicted, edited)
    assertEquals(after.state, SyncState.InSync)
    assertEquals(after.action, ReconcileAction.DoNothing)
  }
