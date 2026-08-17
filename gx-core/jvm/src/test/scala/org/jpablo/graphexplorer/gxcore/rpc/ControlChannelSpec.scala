package org.jpablo.graphexplorer.gxcore.rpc

import munit.FunSuite

import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** The control channel against a real unix socket.
  *
  * A stub SERVER rather than a mocked channel, because everything worth testing
  * here is about the wire: framing, encoding, and what happens when the other
  * end is not what you expect. A test that stubbed the transport would have
  * passed while the id was going out as a string.
  */
class ControlChannelSpec extends FunSuite:

  /** A one-shot server that answers each frame with `reply(request)`. */
  private class StubDesktop(dir: Path, reply: ujson.Obj => String):
    val socket: Path = dir.resolve("control.sock")
    private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    server.bind(UnixDomainSocketAddress.of(socket))

    /** Every request frame the client sent, in order. */
    @volatile var received: Vector[ujson.Obj] = Vector.empty

    private val thread = Thread: () =>
      try
        while true do
          val channel = server.accept()
          try
            var open = true
            while open do
              readFrame(channel) match
                case None => open = false
                case Some(raw) =>
                  val request = ujson.read(raw).obj
                  synchronized { received = received :+ ujson.Obj.from(request) }
                  val response = reply(ujson.Obj.from(request)) + "\n"
                  channel.write(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)))
          catch case NonFatal(_) => ()
          finally channel.close()
      catch case NonFatal(_) => ()
    thread.setDaemon(true)
    thread.start()

    def controlFile: Path =
      val file = dir.resolve("control.json")
      Files.writeString(
        file,
        ujson.Obj("pid" -> 1, "socket" -> socket.toString, "version" -> "test").render()
      )
      file

    def close(): Unit =
      try server.close()
      catch case NonFatal(_) => ()

    private def readFrame(channel: java.nio.channels.SocketChannel): Option[String] =
      val buffer = ByteBuffer.allocate(8192)
      val out    = java.io.ByteArrayOutputStream()
      var done   = false
      var closed = false
      while !done do
        buffer.clear()
        val n = channel.read(buffer)
        if n < 0 then
          done = true
          closed = out.size() == 0
        else
          buffer.flip()
          while buffer.hasRemaining && !done do
            val b = buffer.get()
            if b == '\n'.toByte then done = true else out.write(b.toInt)
      if closed then None else Some(String(out.toByteArray, StandardCharsets.UTF_8))

  private val tmp = FunFixture[Path](
    // A short base dir on purpose: a unix socket address is a fixed-size struct
    // (104 bytes of sun_path on macOS), and the default temp path plus a long
    // test name can overrun it — which fails at bind with a bare EINVAL.
    setup = _ => Files.createTempDirectory("gxrpc").toRealPath(),
    teardown = dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  private def ok(request: ujson.Obj, result: ujson.Value): String =
    ujson.Obj("id" -> request("id"), "ok" -> true, "result" -> result).render()

  tmp.test("a call round-trips through a real socket") { dir =>
    val stub = StubDesktop(dir, r => ok(r, ujson.Obj("echo" -> r("params"))))
    try
      val result = ControlChannel.use(stub.controlFile)(_.call("status", ujson.Obj("a" -> 1)))
      assertEquals(result.map(_("echo")("a").num.toInt), Right(1))
    finally stub.close()
  }

  /** The id went out as `"1"` for the whole of P5's first draft, because ujson
    * maps a Long to a String. Nothing failed — the mismatch check read it as a
    * number, found none, and skipped. The protocol says number, so assert it.
    */
  tmp.test("the request id is a JSON number, not a string") { dir =>
    val stub = StubDesktop(dir, r => ok(r, ujson.Null))
    try
      ControlChannel.use(stub.controlFile)(_.call("status"))
      val id = stub.received.head("id")
      assert(id.numOpt.isDefined, s"id should be a number, got $id")
      assertEquals(id.num, 1.0)
    finally stub.close()
  }

  tmp.test("an id that does not match is a desync, not an answer") { dir =>
    // The failure this guards is subtle: a wrong-but-plausible response would
    // otherwise be returned as though it answered the question asked.
    val stub = StubDesktop(dir, _ => ujson.Obj("id" -> 99, "ok" -> true, "result" -> ujson.Null).render())
    try
      ControlChannel.use(stub.controlFile)(_.call("status")) match
        case Left(ChannelError.Io(message)) => assert(message.contains("out-of-order"), message)
        case other                          => fail(s"expected a desync error, got $other")
    finally stub.close()
  }

  tmp.test("an error frame becomes a typed Rpc error") { dir =>
    val stub = StubDesktop(
      dir,
      r =>
        ujson
          .Obj(
            "id"    -> r("id"),
            "ok"    -> false,
            "error" -> ujson.Obj("code" -> "WATCH_FAILED", "message" -> "blocked by denylist")
          )
          .render()
    )
    try
      ControlChannel.use(stub.controlFile)(_.call("show")) match
        case Left(ChannelError.Rpc(code, message, _)) =>
          assertEquals(code, "WATCH_FAILED")
          assert(message.contains("denylist"), message)
        case other => fail(s"expected an Rpc error, got $other")
    finally stub.close()
  }

  /** V-16 on the wire. A channel read can split mid-character, so a path is only
    * safe if the bytes are decoded once, at the end.
    */
  tmp.test("a non-ASCII path survives the round trip byte for byte") { dir =>
    val stub = StubDesktop(dir, r => ok(r, r("params")))
    try
      val awkward = "/tmp/ünïcode Ø/a\"b\\c.dot"
      val result =
        ControlChannel.use(stub.controlFile)(_.call("show", ujson.Obj("path" -> awkward)))
      assertEquals(result.map(_("path").str), Right(awkward))
    finally stub.close()
  }

  /** The ordinary case, and the one every other command depends on being cheap
    * and quiet: no desktop is a value, not an exception.
    */
  tmp.test("a stale socket file reads as NoDesktop") { dir =>
    val stub = StubDesktop(dir, r => ok(r, ujson.Null))
    val file = stub.controlFile
    stub.close()
    // The socket FILE is still on disk — that is the whole point. Only the
    // connection distinguishes a live desktop from its remains.
    assert(Files.exists(stub.socket))
    ControlChannel.use(file)(_.call("status")) match
      case Left(ChannelError.NoDesktop(_)) => ()
      case other                           => fail(s"expected NoDesktop, got $other")
  }

  tmp.test("a runtime file with no socket recorded is NoDesktop, not a crash") { dir =>
    val file = dir.resolve("control.json")
    Files.writeString(file, ujson.Obj("pid" -> 1, "version" -> "test").render())
    ControlChannel.use(file)(_.call("status")) match
      case Left(ChannelError.NoDesktop(_)) => ()
      case other                           => fail(s"expected NoDesktop, got $other")
  }

  tmp.test("a missing runtime file is NoDesktop") { dir =>
    ControlChannel.use(dir.resolve("absent.json"))(_.call("status")) match
      case Left(ChannelError.NoDesktop(_)) => ()
      case other                           => fail(s"expected NoDesktop, got $other")
  }

  tmp.test("several calls on one connection stay in order") { dir =>
    val stub = StubDesktop(dir, r => ok(r, r("params")))
    try
      val outcome = ControlChannel.use(stub.controlFile): channel =>
        for
          a <- channel.call("status", ujson.Obj("n" -> 1))
          b <- channel.call("status", ujson.Obj("n" -> 2))
          c <- channel.call("status", ujson.Obj("n" -> 3))
        yield Vector(a("n").num.toInt, b("n").num.toInt, c("n").num.toInt)
      assertEquals(outcome, Right(Vector(1, 2, 3)))
      // Ids increment per connection, which is what makes the desync check able
      // to notice anything at all.
      assertEquals(stub.received.map(_("id").num.toInt), Vector(1, 2, 3))
    finally stub.close()
  }
