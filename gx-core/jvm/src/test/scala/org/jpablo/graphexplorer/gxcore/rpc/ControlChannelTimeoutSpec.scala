package org.jpablo.graphexplorer.gxcore.rpc

import munit.FunSuite

import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** A call must end when the desktop does not answer.
  *
  * `readFrame` blocks in `channel.read`, and the channel has no timeout. The
  * desktop bounds its own waits (5 s for a live view command, 45 s for an
  * open), but a desktop that is stopped, deadlocked, or in a debugger answers
  * nothing. Then `gx` waits until something kills it, and an agent that runs
  * `gx` gets no error that it can act on.
  */
class ControlChannelTimeoutSpec extends FunSuite:

  given ExecutionContext = ExecutionContext.global

  /** A desktop that accepts a connection and reads the request, but never
    * answers.
    */
  private class SilentDesktop(dir: Path):
    val socket: Path = dir.resolve("control.sock")
    private val server   = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    private val accepted = ConcurrentLinkedQueue[SocketChannel]()
    server.bind(UnixDomainSocketAddress.of(socket))

    private val thread = Thread: () =>
      try
        while true do
          val channel = server.accept()
          accepted.add(channel)
          val buffer = ByteBuffer.allocate(8192)
          while channel.read(buffer) >= 0 do buffer.clear()
      catch case NonFatal(_) => ()
    thread.setDaemon(true)
    thread.start()

    def controlFile: Path =
      val file = dir.resolve("control.json")
      Files.writeString(file, ujson.Obj("pid" -> 1, "socket" -> socket.toString, "version" -> "test").render())
      file

    /** Close each connection too: that ends a client read that still blocks. */
    def close(): Unit =
      try server.close() catch case NonFatal(_) => ()
      accepted.asScala.foreach(c => try c.close() catch case NonFatal(_) => ())
      thread.join(5_000)

  private val tmp = FunFixture[Path](
    // Short, because a unix socket path has a limit of 104 bytes on macOS.
    setup = _ => Files.createTempDirectory("gxrpc").toRealPath(),
    teardown = dir =>
      Files.walk(dir).sorted(java.util.Comparator.reverseOrder()).forEach(Files.deleteIfExists(_))
  )

  tmp.test("a call to a desktop that does not answer ends with an error after the timeout") { dir =>
    val stub = SilentDesktop(dir)
    try
      val startedAt = System.nanoTime()
      val call      = Future(ControlChannel.use(stub.controlFile, timeout = 300.millis)(_.call("status")))
      // The bound of the test, so that a call with no timeout fails the test
      // and does not hang the suite.
      val result  = Await.result(call, 10.seconds)
      val elapsed = (System.nanoTime() - startedAt).nanos
      result match
        case Left(ChannelError.NoDesktop(detail)) =>
          fail(s"the desktop is there, but it does not answer. NoDesktop is the wrong error: $detail")
        case Left(_)  => ()
        case Right(v) => fail(s"a desktop that never answers gave a result: $v")
      assert(elapsed < 5.seconds, s"the call took $elapsed with a timeout of 300 ms")
    finally stub.close()
  }

  test("the default timeout is longer than the 45 s that the desktop waits for an open"):
    // OPEN_TIMEOUT in desktop/src-tauri/src/main.rs. A shorter default ends a
    // cold-start `gx show` before the desktop gives its own, clearer error.
    assert(ControlChannel.DefaultTimeout > 45.seconds, s"DefaultTimeout is ${ControlChannel.DefaultTimeout}")
