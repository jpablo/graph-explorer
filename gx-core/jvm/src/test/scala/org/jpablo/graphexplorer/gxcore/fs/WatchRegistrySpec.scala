package org.jpablo.graphexplorer.gxcore.fs

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.OriginUri

import java.nio.file.{Files, Path}

/** The debounce, and V-06 (deletion is reported, not swallowed).
  *
  * The clock is injected and [[WatchRegistry.poll]] is called explicitly, so
  * nothing here sleeps. A debounce tested with real time is how an intermittent
  * CI failure gets written.
  */
class WatchRegistrySpec extends FunSuite:

  private val tmp = FunFixture[Path](
    setup = _ => Files.createTempDirectory("gx-core-watch").toRealPath(),
    teardown = dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  /** A registry with a clock the test drives. */
  private class Harness(dir: Path):
    var clock: Long                = 0L
    val audit                      = Audit(dir.resolve("audit.jsonl"))
    val registry: WatchRegistry    = WatchRegistry(audit, debounceMs = 50L, now = () => clock)
    def tick(ms: Long): Unit       = clock += ms
    /** Poll twice with the debounce elapsed between, so a settled change emits. */
    def settle(): Vector[WatchEvent] =
      registry.poll()
      tick(100)
      registry.poll()

  private def write(p: Path, text: String): Unit = Files.writeString(p, text)

  // ------------------------------------------------------------ basics

  tmp.test("a change is reported once it settles, and only once") { dir =>
    val h = Harness(dir)
    val f = dir.resolve("a.dot")
    write(f, "one")
    val uri = FileOrigins.originOf(f, dir)
    h.registry.watch(uri)

    write(f, "two")
    val events = h.settle()
    assertEquals(events.size, 1, s"expected one event, got $events")
    events.head match
      case WatchEvent.Changed(u, hash) =>
        assertEquals(u, uri)
        assertEquals(Some(hash), Documents.hashOf(f))
      case other => fail(s"expected Changed, got $other")

    // No repeats while nothing moves.
    h.tick(100)
    assertEquals(h.registry.poll(), Vector.empty)
  }

  tmp.test("a change is not reported before the debounce has elapsed") { dir =>
    val h   = Harness(dir)
    val f   = dir.resolve("a.dot")
    write(f, "one")
    h.registry.watch(FileOrigins.originOf(f, dir))

    write(f, "two")
    assertEquals(h.registry.poll(), Vector.empty, "reported on first sight")
    h.tick(10)
    assertEquals(h.registry.poll(), Vector.empty, "reported before settling")
    h.tick(100)
    assertEquals(h.registry.poll().size, 1)
  }

  /** Rapid writes coalesce: the caller hears the final content once, not each
    * intermediate state. This is what the debounce is for.
    */
  tmp.test("rapid successive writes coalesce into one event with the final content") { dir =>
    val h = Harness(dir)
    val f = dir.resolve("a.dot")
    write(f, "one")
    h.registry.watch(FileOrigins.originOf(f, dir))

    for text <- List("two", "three", "four") do
      write(f, text)
      h.tick(5)
      assertEquals(h.registry.poll(), Vector.empty)

    h.tick(100)
    val events = h.registry.poll()
    assertEquals(events.size, 1)
    assertEquals(events.head match { case WatchEvent.Changed(_, x) => Some(x); case _ => None }, Documents.hashOf(f))
  }

  // -------------------------------------------------- V-06 deletion

  tmp.test("V-06: deleting a watched file reports it, with the last known hash") { dir =>
    val h   = Harness(dir)
    val f   = dir.resolve("a.dot")
    write(f, "one")
    val uri  = FileOrigins.originOf(f, dir)
    val last = Documents.hashOf(f).getOrElse(fail("gone"))
    h.registry.watch(uri)

    Files.delete(f)
    val events = h.registry.poll()
    assertEquals(events, Vector(WatchEvent.Deleted(uri, last)), "v1 emitted nothing at all here")
  }

  tmp.test("V-06: deletion is reported once, not on every poll") { dir =>
    val h = Harness(dir)
    val f = dir.resolve("a.dot")
    write(f, "one")
    h.registry.watch(FileOrigins.originOf(f, dir))
    Files.delete(f)
    assertEquals(h.registry.poll().size, 1)
    h.tick(100)
    assertEquals(h.registry.poll(), Vector.empty)
  }

  /** Delete-then-create is how several editors save. The pair must read as
    * "gone, then back", not as a permanent loss.
    */
  tmp.test("V-06: a file that comes back is Restored, not silently re-adopted") { dir =>
    val h   = Harness(dir)
    val f   = dir.resolve("a.dot")
    write(f, "one")
    val uri = FileOrigins.originOf(f, dir)
    h.registry.watch(uri)

    Files.delete(f)
    assertEquals(h.registry.poll().size, 1)

    write(f, "back again")
    val events = h.settle()
    assertEquals(events.size, 1)
    assert(events.head.isInstanceOf[WatchEvent.Restored], s"expected Restored, got ${events.head}")
  }

  tmp.test("V-06: deletion reaches the audit log") { dir =>
    val h = Harness(dir)
    val f = dir.resolve("a.dot")
    write(f, "one")
    h.registry.watch(FileOrigins.originOf(f, dir))
    Files.delete(f)
    h.registry.poll()
    assert(h.audit.entries.exists(_.contains("origin.missing")), h.audit.entries.mkString("\n"))
  }

  // ------------------------------------------------ registry behaviour

  tmp.test("watching is idempotent, and unwatch reports whether it did anything") { dir =>
    val h   = Harness(dir)
    val f   = dir.resolve("a.dot")
    write(f, "one")
    val uri = FileOrigins.originOf(f, dir)

    h.registry.watch(uri)
    h.registry.watch(uri)
    assertEquals(h.registry.watched, Vector(uri))
    assert(h.registry.unwatch(uri))
    assert(!h.registry.unwatch(uri))
    assertEquals(h.registry.watched, Vector.empty)
  }

  /** §5.4: the registry is keyed by canonical URI, so a file reached by two
    * spellings is watched once — not once per spelling.
    */
  tmp.test("§5.4: two spellings of one file produce a single watch entry") { dir =>
    assume(
      try
        val t = dir.resolve("probe"); Files.writeString(t, "x")
        Files.createSymbolicLink(dir.resolve("probe-link"), t); true
      catch case _: Throwable => false,
      "symlinks unavailable"
    )
    val h      = Harness(dir)
    val target = dir.resolve("real.dot")
    write(target, "one")
    val link = dir.resolve("link.dot")
    Files.createSymbolicLink(link, target)

    h.registry.watch(FileOrigins.originOf(target, dir))
    h.registry.watch(FileOrigins.originOf(link, dir))
    assertEquals(h.registry.watched.size, 1, "one file was watched twice")

    write(target, "two")
    assertEquals(h.settle().size, 1, "one change produced more than one event")
  }

  tmp.test("an unwatched origin produces nothing") { dir =>
    val h = Harness(dir)
    val f = dir.resolve("a.dot")
    write(f, "one")
    write(f, "two")
    assertEquals(h.registry.poll(), Vector.empty)
  }
