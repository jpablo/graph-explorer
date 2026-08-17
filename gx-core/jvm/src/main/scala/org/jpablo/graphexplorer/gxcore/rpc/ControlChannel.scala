package org.jpablo.graphexplorer.gxcore.rpc

import java.io.ByteArrayOutputStream
import java.net.{StandardProtocolFamily, UnixDomainSocketAddress}
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal

/** Why a call did not produce an answer.
  *
  * `NoDesktop` is separated from `Io` on purpose: it is the ONE outcome that is
  * ordinary rather than broken. Every `gx` command except the session tier works
  * without a desktop (D5), so "there is no window" must be reportable as a fact,
  * not raised as a failure.
  */
enum ChannelError derives CanEqual:
  case NoDesktop(detail: String)
  case Io(message: String)
  case Rpc(code: String, message: String, details: ujson.Obj)

  def describe: String = this match
    case NoDesktop(_)     => "no desktop is running"
    case Io(message)      => message
    case Rpc(_, message, _) => message

/** The desktop's control channel: a unix socket carrying one JSON object per
  * line, request then response (D4).
  *
  * There is no credential here and no place to put one. v1's client read a token
  * out of the runtime file and set an `Authorization` header; the socket's own
  * permissions have replaced that entirely, so connecting IS the authorization.
  *
  * AF_UNIX on every platform, including Windows — see the desktop's `main.rs`
  * for why one transport rather than D4's two.
  */
final class ControlChannel private (channel: SocketChannel, debug: String => Unit):

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
    debug(s"-> $line")
    try
      channel.write(ByteBuffer.wrap((line + "\n").getBytes(StandardCharsets.UTF_8)))
      readFrame() match
        case None =>
          Left(ChannelError.Io(s"desktop closed the connection during '$method'"))
        case Some(raw) =>
          debug(s"<- $raw")
          parseResponse(raw, id)
    catch case NonFatal(e) => Left(ChannelError.Io(s"$method failed: ${e.getMessage}"))

  def close(): Unit =
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
  private def readFrame(): Option[String] =
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
      else
        buffer.flip()
        while buffer.hasRemaining && !done do
          val b = buffer.get()
          if b == '\n'.toByte then done = true else out.write(b.toInt)
    if closed then None else Some(String(out.toByteArray, StandardCharsets.UTF_8))

object ControlChannel:

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
  def connect(socket: Path, debug: String => Unit = _ => ()): Either[ChannelError, ControlChannel] =
    try
      val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
      try
        channel.connect(UnixDomainSocketAddress.of(socket))
        debug(s"connected to $socket")
        Right(ControlChannel(channel, debug))
      catch
        case NonFatal(e) =>
          channel.close()
          Left(ChannelError.NoDesktop(s"$socket: ${e.getMessage}"))
    catch case NonFatal(e) => Left(ChannelError.Io(s"cannot open a unix socket: ${e.getMessage}"))

  /** Connect, run, close — the shape every caller actually wants. */
  def use[A](
      controlFile: Path,
      debug:       String => Unit = _ => ()
  )(f: ControlChannel => Either[ChannelError, A]): Either[ChannelError, A] =
    socketPathFrom(controlFile) match
      case None => Left(ChannelError.NoDesktop(s"no socket recorded in $controlFile"))
      case Some(socket) =>
        connect(socket, debug).flatMap: channel =>
          try f(channel)
          finally channel.close()
