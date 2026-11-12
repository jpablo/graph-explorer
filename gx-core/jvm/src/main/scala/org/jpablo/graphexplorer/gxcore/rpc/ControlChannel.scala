package org.jpablo.graphexplorer.gxcore.rpc

import java.io.{ByteArrayOutputStream, IOException, InterruptedIOException}
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.{SelectionKey, Selector, SocketChannel}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.util.control.{NoStackTrace, NonFatal}

/** Why a call did not produce an answer.
  *
  * `NoDesktop` is separated from `Io` on purpose: it is the ONE outcome that is
  * ordinary rather than broken. Every `gx` command works without a desktop
  * (D5), except a live view command. Thus "there is no window" must be
  * reportable as a fact, not raised as a failure.
  */
enum ChannelError derives CanEqual:
  case NoDesktop(detail: String)
  case Io(message: String)
  case Rpc(code: String, message: String, details: ujson.Obj)

/** The desktop's control channel: a unix socket carrying one JSON object per
  * line, request then response (D4).
  *
  * There is no credential here and no place to put one. v1's client read a token
  * out of the runtime file and set an `Authorization` header; the socket's own
  * permissions have replaced that entirely, so connecting IS the authorization.
  *
  * AF_UNIX on every platform, including Windows — see the desktop's `main.rs`
  * for why one transport rather than D4's two.
  *
  * Each call has a time limit, `timeout`. The limit starts when the call
  * starts, and it covers the send of the request and the read of the full
  * answer. A NIO channel has no read timeout, and `channel.socket()` does not
  * work for a unix socket. Thus the channel is in non-blocking mode, and a
  * `Selector` waits for it with a time limit.
  */
final class ControlChannel private (channel: SocketChannel, timeout: FiniteDuration, debug: String => Unit):
  import ControlChannel.TimedOut

  // The selector has only this channel. The key has no interest until a call
  // waits for a read or a write.
  private val selector = Selector.open()
  private val key      = channel.register(selector, 0)

  // Strict equality is on project-wide, and ujson.Value carries no CanEqual.
  // Comparing two ids structurally is exactly what is wanted here, so the
  // permission is granted in the one place it applies rather than by weakening
  // the id to something the compiler already knows how to compare.
  private given CanEqual[ujson.Value, ujson.Value] = CanEqual.derived

  /** Send one request, read one response.
    *
    * Synchronous and in-order by construction: this is a request/response
    * channel, not a multiplexed one. The `id` is echoed and checked anyway, so a
    * desynchronized stream is caught rather than silently answering the wrong
    * question.
    */
  def call(method: String, params: ujson.Obj = ujson.Obj()): Either[ChannelError, ujson.Value] =
    val id      = nextId()
    val request = ujson.Obj("id" -> id, "method" -> method, "params" -> params)
    // `id` is a ujson.Value, not a Long, and that is not incidental: ujson maps
    // Long to a STRING (a Num is a Double and cannot round-trip a Long), so an
    // id built from a Long went out as `"1"`, came back as `"1"`, and the
    // mismatch check below — which read it as a number — quietly matched
    // nothing on every single call.
    val line    = ujson.write(request)
    if !channel.isOpen then Left(ChannelError.Io(s"cannot send '$method': the connection is closed"))
    else
      debug(s"-> $line")
      val deadline = System.nanoTime() + timeout.toNanos
      try
        send(ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8)), deadline)
        readFrame(deadline) match
          case None =>
            Left(ChannelError.Io(s"desktop closed the connection during '$method'"))
          case Some(raw) =>
            debug(s"<- $raw")
            parseResponse(raw, id)
      catch
        case TimedOut(step) =>
          // A late answer can come after this. The next call on this
          // connection then reads it as its own answer. Close the connection,
          // so that a next call fails at once with a clear error.
          close()
          Left(ChannelError.Io(s"the desktop did not $step '$method' in $timeout"))
        case NonFatal(e) => Left(ChannelError.Io(s"$method failed: ${e.getMessage}"))

  def close(): Unit =
    // Close the selector first. The JDK closes the socket of a registered
    // channel only after its key is gone.
    try selector.close()
    catch case NonFatal(_) => ()
    try channel.close()
    catch case NonFatal(_) => ()

  private var counter = 0L
  private def nextId(): ujson.Value =
    counter += 1
    ujson.Num(counter.toDouble)

  private def parseResponse(raw: String, expectedId: ujson.Value): Either[ChannelError, ujson.Value] =
    try
      val response = ujson.read(raw).obj
      // Compared as VALUES, so a type change on either side is a mismatch
      // rather than something the check skips. An absent id is allowed: a frame
      // rejected before it parsed has no id to echo.
      response.get("id") match
        case Some(id) if id != expectedId =>
          Left(ChannelError.Io(s"out-of-order response: expected id $expectedId, got $id"))
        case _ =>
          if response.get("ok").flatMap(_.boolOpt).getOrElse(false) then
            Right(response.getOrElse("result", ujson.Null))
          else
            val error = response.get("error").flatMap(_.objOpt).map(ujson.Obj.from).getOrElse(ujson.Obj())
            Left(
              ChannelError.Rpc(
                code = error.value.get("code").flatMap(_.strOpt).getOrElse("UNKNOWN"),
                message = error.value.get("message").flatMap(_.strOpt).getOrElse("request failed"),
                details = error
              )
            )
    catch case NonFatal(e) => Left(ChannelError.Io(s"unreadable response: ${e.getMessage}"))

  /** Frames are newline-delimited, so a read is a scan for '\n'.
    *
    * Bytes are accumulated and decoded ONCE, at the end: a channel read can
    * split anywhere, including the middle of a multi-byte character, and
    * decoding each chunk would corrupt exactly the non-ASCII paths V-16 is
    * about. UTF-8 is named rather than defaulted for the same reason.
    */
  private def readFrame(deadline: Long): Option[String] =
    val buffer = ByteBuffer.allocate(16 * 1024)
    val out    = ByteArrayOutputStream()
    var done   = false
    var closed = false
    while !done do
      buffer.clear()
      val n = channel.read(buffer)
      if n < 0 then
        done = true
        closed = out.size() == 0
      else if n == 0 then await(SelectionKey.OP_READ, deadline, "answer")
      else
        buffer.flip()
        while buffer.hasRemaining && !done do
          val b = buffer.get()
          if b == '\n'.toByte then done = true else out.write(b.toInt)
        // A peer that sends bytes with no end of line must also stop at the
        // deadline, so do the check here too, and not only in `await`.
        if !done && System.nanoTime() - deadline >= 0 then throw TimedOut("answer")
    if closed then None else Some(String(out.toByteArray, StandardCharsets.UTF_8))

  /** A write in non-blocking mode can write only a part of the buffer, or
    * nothing. A desktop that does not read fills the socket buffer, and then
    * the write cannot continue. Thus the write also stops at the deadline.
    */
  private def send(buffer: ByteBuffer, deadline: Long): Unit =
    while buffer.hasRemaining do
      if channel.write(buffer) == 0 then await(SelectionKey.OP_WRITE, deadline, "read the request")
      else if buffer.hasRemaining && System.nanoTime() - deadline >= 0 then throw TimedOut("read the request")

  /** Wait until the channel is ready for `op`. Throw `TimedOut` if the
    * deadline comes first.
    */
  private def await(op: Int, deadline: Long, step: String): Unit =
    val _     = key.interestOps(op)
    var ready = false
    while !ready do
      val left = deadline - System.nanoTime()
      if left <= 0 then throw TimedOut(step)
      // A select on an interrupted thread returns at once. Without this check
      // the loop uses all of a CPU until the deadline.
      if Thread.currentThread().isInterrupted then
        throw InterruptedIOException(s"interrupted while the desktop had to $step")
      // `select(0)` waits with no limit. Thus the value is 1 ms or more.
      val _ = selector.select(left / 1_000_000 + 1)
      // Remove the key from the selected set. A key that stays in the set
      // makes the next `select` return 0, also when the channel is ready.
      ready = selector.selectedKeys().remove(key)

object ControlChannel:

  /** The time limit of one call: the send of the request and the read of the
    * answer.
    *
    * The desktop sets a time limit on its own waits (desktop/src-tauri/src/main.rs).
    * A live view command waits 5 s (`LIVE_VIEW_TIMEOUT`). A `show` can wait two
    * times `OPEN_TIMEOUT` (45 s), one after the other: first `wait_for_page`
    * waits for the page, then `await_open` waits for the acknowledgment. Thus
    * the desktop can need 90 s before it sends its own error.
    *
    * This value is 90 s and a margin of 10 s. Thus a slow cold start gets the
    * desktop's own error, which tells more. A shorter limit can stop a `show`
    * that the desktop then completes, and gx reports a failure for a file that
    * the window shows. This limit stops only a desktop that answers nothing: a
    * desktop that is stopped, deadlocked, or in a debugger. Make it longer if
    * the desktop gets a new wait.
    */
  val DefaultTimeout: FiniteDuration = 100.seconds

  /** The deadline of a call came before the step completed. `step` completes
    * the sentence "the desktop did not ...".
    */
  private final case class TimedOut(step: String)
      extends IOException(s"the desktop did not $step in time")
      with NoStackTrace

  /** Where the desktop said its socket is.
    *
    * Read from the runtime file rather than reconstructed, so the two sides
    * cannot disagree about the path — and so a future change to it needs no
    * matching change here.
    */
  def socketPathFrom(controlFile: Path): Option[Path] =
    try
      if !Files.isRegularFile(controlFile) then None
      else
        ujson
          .read(Files.readString(controlFile, StandardCharsets.UTF_8))
          .obj
          .get("socket")
          .flatMap(_.strOpt)
          .filter(_.nonEmpty)
          .map(Path.of(_))
    catch case NonFatal(_) => None

  /** Connect, or say why not.
    *
    * A stale socket file is the case that matters: a crashed desktop leaves one
    * behind, so its existence proves nothing and `connect` is what settles it.
    * That is strictly better than v1's liveness check, which believed the file.
    */
  def connect(
      socket:  Path,
      debug:   String => Unit = _ => (),
      timeout: FiniteDuration = DefaultTimeout
  ): Either[ChannelError, ControlChannel] =
    try
      val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
      // The connect is in blocking mode, with no time limit. A unix socket
      // connects or fails at once, with one exception: on Linux, a connect
      // waits when the listen backlog of the desktop is full. That occurs
      // only after hundreds of calls to a desktop that does not accept. In
      // non-blocking mode, Linux gives EAGAIN, which shows here as NoDesktop.
      val connected =
        try
          val _ = channel.connect(UnixDomainSocketAddress.of(socket))
          Right(())
        catch
          case NonFatal(e) =>
            channel.close()
            Left(ChannelError.NoDesktop(s"$socket: ${e.getMessage}"))
      connected.flatMap: _ =>
        try
          val _ = channel.configureBlocking(false)
          val c = ControlChannel(channel, timeout, debug)
          debug(s"connected to $socket")
          Right(c)
        catch
          case NonFatal(e) =>
            channel.close()
            Left(ChannelError.Io(s"cannot use the connection to $socket: ${e.getMessage}"))
    catch case NonFatal(e) => Left(ChannelError.Io(s"cannot open a unix socket: ${e.getMessage}"))

  /** Connect, run, close — the shape every caller actually wants. */
  def use[A](
      controlFile: Path,
      debug:       String => Unit = _ => (),
      timeout:     FiniteDuration = DefaultTimeout
  )(f: ControlChannel => Either[ChannelError, A]): Either[ChannelError, A] =
    socketPathFrom(controlFile) match
      case None => Left(ChannelError.NoDesktop(s"no socket recorded in $controlFile"))
      case Some(socket) =>
        connect(socket, debug, timeout).flatMap: channel =>
          try f(channel)
          finally channel.close()
