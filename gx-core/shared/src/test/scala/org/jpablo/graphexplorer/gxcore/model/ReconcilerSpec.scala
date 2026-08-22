package org.jpablo.graphexplorer.gxcore.model

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.fs.LineEnding

/** Phase 3: the reconciliation engine, now reachable from both callers.
  *
  * These tests exercise the DECISION with no filesystem, which is the point of
  * moving it: the desktop's page has no filesystem either, and it has to reach
  * the same answers `gx sync` reaches.
  */
class ReconcilerSpec extends FunSuite:

  /** A stand-in digest. The reconciler never inspects a hash, it only compares
    * hashes, so a fake that is injective over the inputs proves the same thing
    * a real SHA-256 would — and proves it without a platform.
    */
  private def fakeHash(text: String, lineEnding: LineEnding): ContentHash =
    ContentHash.fromHex(f"${(lineEnding.applyTo(text)).hashCode & 0xffffffffL}%08x")

  private def binding(mode: SyncMode, base: ContentHash) =
    Binding(OriginUri.parse("file:///tmp/a.dot").toOption.get, mode, base, lastSyncAt = 0L)

  private def snapshot(text: String, lineEnding: LineEnding = LineEnding.Lf) =
    OriginSnapshot(text, fakeHash(text, lineEnding), lineEnding)

  private def plan(mode: SyncMode, base: String, local: String, origin: Option[OriginSnapshot]) =
    Reconciler.plan(Some(binding(mode, fakeHash(base, LineEnding.Lf))), local, origin, fakeHash)

  test("no binding is Unbound, and asks for nothing") {
    val result = Reconciler.plan(None, "digraph G { a }", None, fakeHash)

    assertEquals(result, ReconcilePlan.Unbound)
    assertEquals(result.state, SyncState.InSync)
    assertEquals(result.action, ReconcileAction.DoNothing)
  }

  test("Pull and Behind adopts the text the caller already read") {
    val origin = snapshot("digraph G { changed }")
    val result = plan(SyncMode.Pull, base = "agreed", local = "agreed", origin = Some(origin))

    assertEquals(result.state, SyncState.Behind)
    assertEquals(result.action, ReconcileAction.AdoptOrigin("digraph G { changed }", origin.hash))
  }

  test("Push and Ahead writes against the AGREED base, not against the new text") {
    // The compare-and-swap asks whether the file is still what both sides
    // agreed on. Expecting `local` would ask whether the file already holds
    // what we are about to write — true exactly when the write is pointless.
    val agreed = fakeHash("agreed", LineEnding.Lf)
    val result = plan(SyncMode.Push, base = "agreed", local = "mine", origin = Some(snapshot("agreed")))

    assertEquals(result.state, SyncState.Ahead)
    assertEquals(result.action, ReconcileAction.WriteOrigin("mine", agreed))
  }

  test("Pull refuses to push, and Push refuses to pull") {
    assertEquals(
      plan(SyncMode.Pull, base = "agreed", local = "mine", origin = Some(snapshot("agreed"))).action,
      ReconcileAction.DoNothing,
      "a Pull binding must keep local edits local (§5.3)"
    )
    assertEquals(
      plan(SyncMode.Push, base = "agreed", local = "agreed", origin = Some(snapshot("theirs"))).action,
      ReconcileAction.DoNothing
    )
  }

  test("both sides moved to the same content advances the base, and does no I/O") {
    // The row that is easy to omit and expensive to omit: a generator that
    // rewrites a file to byte-identical content hits this on every run.
    val result = plan(SyncMode.Sync, base = "agreed", local = "same", origin = Some(snapshot("same")))

    assertEquals(result.state, SyncState.Converged)
    assertEquals(result.action, ReconcileAction.AdvanceBase(fakeHash("same", LineEnding.Lf)))
  }

  test("divergence asks for nothing, and reports the local hash so both sides can be named") {
    val result = plan(SyncMode.Sync, base = "agreed", local = "mine", origin = Some(snapshot("theirs")))

    assertEquals(result.state, SyncState.Diverged)
    assertEquals(result.action, ReconcileAction.DoNothing)
    result match
      case ReconcilePlan.Bound(_, _, local) => assertEquals(local, fakeHash("mine", LineEnding.Lf))
      case other                            => fail(s"expected a bound plan, got $other")
  }

  test("a missing origin is OriginMissing whatever the local text says") {
    val result = plan(SyncMode.Sync, base = "agreed", local = "mine", origin = None)

    assertEquals(result.state, SyncState.OriginMissing)
    assertEquals(result.action, ReconcileAction.DoNothing)
  }

  // ------------------------------------------------- the CRLF rule, moved here

  test("a CRLF origin nobody has touched is InSync, not Ahead") {
    // The rule this phase moved out of the command-line tool. `local` is the
    // record's text as it would be written into THIS file, so it is hashed with
    // the file's own convention. Hashing with a fixed LF made every
    // CRLF-authored origin read `Ahead` forever, with nothing edited.
    val text   = "digraph G {\na -> b\n}"
    val origin = snapshot(text, LineEnding.Crlf)
    val result = Reconciler.plan(
      Some(binding(SyncMode.Sync, origin.hash)),
      text,
      Some(origin),
      fakeHash
    )

    assertEquals(result.state, SyncState.InSync)
    assertEquals(result.action, ReconcileAction.DoNothing)
  }

  test("a byte-identical CRLF regeneration is Converged, not Diverged") {
    val text     = "digraph G {\na -> b\n}"
    val origin   = snapshot(text, LineEnding.Crlf)
    val staleBase = fakeHash("something older", LineEnding.Crlf)
    val result = Reconciler.plan(Some(binding(SyncMode.Sync, staleBase)), text, Some(origin), fakeHash)

    assertEquals(result.state, SyncState.Converged)
    assertEquals(result.action, ReconcileAction.AdvanceBase(origin.hash))
  }

  test("with no origin on disk, local is hashed as LF") {
    // Lf is what a re-created file would get, so that is the convention the
    // absent file is measured against. Nothing acts on it — the state is
    // OriginMissing either way — but the reported hash must not be arbitrary.
    val result = Reconciler.plan(
      Some(binding(SyncMode.Sync, fakeHash("agreed", LineEnding.Lf))),
      "digraph G {\na\n}",
      None,
      fakeHash
    )

    result match
      case ReconcilePlan.Bound(_, _, local) =>
        assertEquals(local, fakeHash("digraph G {\na\n}", LineEnding.Lf))
      case other => fail(s"expected a bound plan, got $other")
  }
