package org.jpablo.graphexplorer.gxcore.model

import munit.FunSuite

/** The reconciliation table from docs/sources-and-library-architecture.md §5.2,
  * asserted row by row rather than left as prose.
  */
class SyncStateSpec extends FunSuite:

  private val base   = ContentHash.fromHex("aa")
  private val other  = ContentHash.fromHex("bb")
  private val third  = ContentHash.fromHex("cc")

  private def state(local: ContentHash, remote: ContentHash) =
    SyncState.of(base, local, Some(remote))

  test("neither side moved -> InSync") {
    assertEquals(state(base, base), SyncState.InSync)
  }

  test("local moved, origin did not -> Ahead") {
    assertEquals(state(other, base), SyncState.Ahead)
  }

  test("origin moved, local did not -> Behind") {
    assertEquals(state(base, other), SyncState.Behind)
  }

  test("both moved, differently -> Diverged") {
    assertEquals(state(other, third), SyncState.Diverged)
  }

  /** The row that carries the primary use case. A generator rewriting a file to
    * byte-identical content must not register as a conflict, or every LLM run
    * would produce one.
    */
  test("both moved to the SAME content -> Converged, not Diverged") {
    assertEquals(state(other, other), SyncState.Converged)
  }

  test("origin gone -> OriginMissing, distinct from Diverged") {
    assertEquals(SyncState.of(base, other, None), SyncState.OriginMissing)
    assertEquals(SyncState.of(base, base, None), SyncState.OriginMissing)
  }

  test("only Diverged and OriginMissing need a human") {
    import SyncState.*
    assert(Diverged.needsUser)
    assert(OriginMissing.needsUser)
    assert(!InSync.needsUser)
    assert(!Ahead.needsUser)
    assert(!Behind.needsUser)
    assert(!Converged.needsUser)
  }

  // ---------------------------------------------------------------- modes

  test("Pull follows the origin but never writes back") {
    import SyncMode.*
    assertEquals(Pull.autoAction(SyncState.Behind), Some(SyncAction.Pull))
    assertEquals(Pull.autoAction(SyncState.Ahead), None)
  }

  /** §5.3: a local edit to a Pull diagram is a stable resting state. It must not
    * be pushed, and — the part that protects the user's work — the divergence it
    * later causes must not resolve itself by discarding those edits.
    */
  test("Pull with local edits does not push, and Diverged never auto-resolves") {
    import SyncMode.*
    assertEquals(Pull.autoAction(SyncState.Ahead), None)
    for mode <- SyncMode.values do
      assertEquals(mode.autoAction(SyncState.Diverged), None, s"$mode auto-resolved a conflict")
      assertEquals(mode.autoAction(SyncState.OriginMissing), None, s"$mode acted on a missing origin")
  }

  test("Push writes back but never follows") {
    import SyncMode.*
    assertEquals(Push.autoAction(SyncState.Ahead), Some(SyncAction.Push))
    assertEquals(Push.autoAction(SyncState.Behind), None)
  }

  test("Sync moves in both directions") {
    import SyncMode.*
    assertEquals(Sync.autoAction(SyncState.Ahead), Some(SyncAction.Push))
    assertEquals(Sync.autoAction(SyncState.Behind), Some(SyncAction.Pull))
  }

  test("Detached does nothing in any state") {
    for s <- SyncState.values do
      assertEquals(
        SyncMode.Detached.autoAction(s).filter(_ != SyncAction.AdvanceBase),
        None,
        s"Detached acted on $s"
      )
  }

  /** Converged is settled by bookkeeping, not I/O — in every mode, including
    * ones that forbid the direction the content happened to move.
    */
  test("Converged advances the baseline in every mode, with no transfer") {
    for mode <- SyncMode.values do
      assertEquals(mode.autoAction(SyncState.Converged), Some(SyncAction.AdvanceBase), s"mode $mode")
  }

  test("InSync asks for nothing") {
    for mode <- SyncMode.values do assertEquals(mode.autoAction(SyncState.InSync), None)
  }
