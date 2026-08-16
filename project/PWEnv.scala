package jsenv.playwright

import com.microsoft.playwright.{Browser, BrowserType, Page, Playwright}
import org.scalajs.jsenv.{Input, JSComRun, JSRun, JSEnv, RunConfig, UnsupportedInputException}

import java.io.*
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.jdk.CollectionConverters.*

/** Minimal Playwright-backed JSEnv, adapted from https://github.com/gmkumar2005/scala-js-env-playwright (BSD-3-Clause).
  *
  * Upstream is published for sbt 1.x only (Scala 2.12) and cannot load on sbt 2.x, whose build definition runs on Scala 3. This port drops
  * the cats-effect / scribe / jimfs machinery in favor of a plain `Future` + blocking poll loop; the browser-side protocol is unchanged: a
  * setup script installs `scalajsPlayWrightInternalInterface` (fetch/send) which the JVM side polls via `page.evaluate`.
  *
  * Runtime deps (declared in project/plugins.sbt): com.microsoft.playwright `playwright` and `driver-bundle`. The driver-bundle jar
  * self-registers via ServiceLoader; the system property set below pins the same class explicitly so driver discovery does not depend on
  * ServiceLoader behavior in sbt's layered classloaders.
  */
class PWEnv(
    browserName:             String = "chromium",
    headless:                Boolean = true,
    showLogs:                Boolean = false,
    launchOptions:           List[String] = Nil,
    additionalLaunchOptions: List[String] = Nil
) extends JSEnv:

  System.setProperty("playwright.driver.impl", "com.microsoft.playwright.impl.driver.jar.DriverJar")

  override val name: String = s"playwright-$browserName"

  private val validator = RunConfig.Validator().supportsInheritIO().supportsOnOutputStream()

  override def start(input: Seq[Input], runConfig: RunConfig): JSRun =
    validator.validate(runConfig)
    new PWRun(browserName, headless, showLogs, launchOptions, additionalLaunchOptions, input, runConfig, enableCom = false, _ => ())

  override def startWithCom(input: Seq[Input], runConfig: RunConfig, onMessage: String => Unit): JSComRun =
    validator.validate(runConfig)
    new PWRun(browserName, headless, showLogs, launchOptions, additionalLaunchOptions, input, runConfig, enableCom = true, onMessage)

end PWEnv

private final class PWRun(
    browserName:             String,
    headless:                Boolean,
    showLogs:                Boolean,
    launchOptions:           List[String],
    additionalLaunchOptions: List[String],
    input:                   Seq[Input],
    runConfig:               RunConfig,
    enableCom:               Boolean,
    onMessage:               String => Unit
) extends JSComRun:

  private val closed    = new AtomicBoolean(false)
  private val sendQueue = new ConcurrentLinkedQueue[String]()
  private val intf      = "this.scalajsPlayWrightInternalInterface"

  override def send(msg: String): Unit = sendQueue.offer(msg)

  override def close(): Unit = closed.set(true)

  override lazy val future: Future[Unit] =
    Future(blocking(withDriverClassLoader(runLoop())))(using ExecutionContext.global)

  /** Playwright's `DriverJar` locates its bundled driver with
    * `Thread.currentThread().getContextClassLoader().getResource("driver/<platform>")`. `runLoop` runs on `ExecutionContext.global`, whose
    * ForkJoin workers inherit the *system* classloader -- which carries no driver-bundle jar, so that lookup returns null and
    * `Playwright.create()` dies with an NPE. Pin the context classloader to the one that loaded this class: PWEnv and driver-bundle both
    * live on the meta-build classpath, so it can see `driver/`.
    */
  private def withDriverClassLoader[A](body: => A): A =
    val thread = Thread.currentThread()
    val saved  = thread.getContextClassLoader
    thread.setContextClassLoader(classOf[PWEnv].getClassLoader)
    try body
    finally thread.setContextClassLoader(saved)

  private def runLoop(): Unit =
    val playwright = Playwright.create()
    try
      val browserType = browserName.toLowerCase match
        case "chromium" | "chrome" => playwright.chromium()
        case "firefox"             => playwright.firefox()
        case "webkit"              => playwright.webkit()
        case other                 => throw new IllegalArgumentException(s"Invalid browser type: $other")

      val defaultArgs = browserName.toLowerCase match
        case "firefox" =>
          List("--disable-web-security")
        case "webkit" =>
          List(
            "--disable-extensions",
            "--disable-web-security",
            "--allow-running-insecure-content",
            "--disable-site-isolation-trials",
            "--allow-file-access-from-files"
          )
        case _ => // chromium / chrome
          List(
            "--disable-extensions",
            "--disable-web-security",
            "--allow-running-insecure-content",
            "--disable-site-isolation-trials",
            "--allow-file-access-from-files",
            "--disable-gpu"
          )

      val args = (if launchOptions.isEmpty then defaultArgs else launchOptions) ++ additionalLaunchOptions
      if showLogs then println(s"[PWEnv] launching $browserName (headless=$headless)")
      val browser = browserType.launch(new BrowserType.LaunchOptions().setHeadless(headless).setArgs(args.asJava))
      try
        val page = browser.newContext().newPage()
        try
          val setupJs  = writeTemp("setup.js", setupCode(enableCom))
          val htmlPage = writeTemp("scalajsRun.html", htmlDocument(Input.Script(setupJs) +: input))
          page.navigate(htmlPage.toUri.toURL.toString)
          pollLoop(page)
        finally
          page.close()
      finally
        browser.close()
    finally
      playwright.close()

  /** Polls the page for console output / errors / com messages until `close()` is called. Throws WindowOnErrorException if the page reports
    * a window.onerror.
    */
  private def pollLoop(page: Page): Unit =
    val streams = prepareStreams(runConfig)
    try
      while !closed.get() && !isInterfaceUp(page) do Thread.sleep(100)
      while !closed.get() do
        drainQueue(page)
        val data = page.evaluate(s"$intf.fetch();").asInstanceOf[java.util.Map[String, java.util.List[String]]]
        printAll(streams.out, data.get("consoleLog"))
        printAll(streams.out, data.get("consoleError"))
        printAll(streams.out, data.get("errors"))
        val msgs = data.get("msgs")
        if msgs != null then msgs.forEach(msg => onMessage(msg))
        val errors = data.get("errors")
        if errors != null && !errors.isEmpty then
          throw WindowOnErrorException(errors.asScala.toList.mkString("\n"))
        Thread.sleep(100)
    finally
      streams.close()

  private def isInterfaceUp(page: Page): Boolean =
    page.evaluate(s"!!$intf;").asInstanceOf[Boolean]

  private def drainQueue(page: Page): Unit =
    var msg = sendQueue.poll()
    while msg != null do
      page.evaluate(s"function(arg) { $intf.send(arg); }", msg)
      if sys.env.getOrElse("PWDEBUG", "0") == "1" then page.pause()
      msg = sendQueue.poll()

  private def printAll(out: PrintStream, lines: java.util.List[String]): Unit =
    if lines != null then lines.forEach(line => out.println(line))

  private def writeTemp(name: String, content: String): Path =
    val path = Files.createTempFile(null, s"-$name")
    Files.writeString(path, content)
    path.toFile.deleteOnExit()
    path

  private def htmlDocument(fullInput: Seq[Input]): String =
    val tags = fullInput.map {
      case Input.Script(path)         => scriptTag(path, "text/javascript")
      case Input.CommonJSModule(path) => scriptTag(path, "text/javascript")
      case Input.ESModule(path)       => scriptTag(path, "module")
      case _                          => throw UnsupportedInputException(fullInput)
    }
    s"""<html>
       |  <meta charset="UTF-8">
       |  <body>
       |    ${tags.mkString("\n    ")}
       |  </body>
       |</html>
       |""".stripMargin

  // Input files are real build artifacts; reference them in place via file:// URLs
  // (their .map files sit alongside, so source maps keep working).
  private def scriptTag(path: Path, tpe: String): String =
    s"<script defer type='$tpe' src='${path.toUri.toURL}'></script>"

  private def setupCode(enableCom: Boolean): String =
    s"""
       |(function() {
       |  // Buffers for console.log / console.error
       |  var consoleLog = [];
       |  var consoleError = [];
       |
       |  // Buffer for errors.
       |  var errors = [];
       |
       |  // Buffer for outgoing messages.
       |  var outMessages = [];
       |
       |  // Buffer for incoming messages (used if onMessage not initalized).
       |  var inMessages = [];
       |
       |  // Callback for incoming messages.
       |  var onMessage = null;
       |
       |  function captureConsole(fun, buf) {
       |    if (!fun) return fun;
       |    return function() {
       |      var strs = []
       |      for (var i = 0; i < arguments.length; ++i)
       |        strs.push(String(arguments[i]));
       |
       |      buf.push(strs.join(" "));
       |      return fun.apply(this, arguments);
       |    }
       |  }
       |
       |  console.log = captureConsole(console.log, consoleLog);
       |  console.error = captureConsole(console.error, consoleError);
       |
       |  window.addEventListener('error', function(e) {
       |    errors.push(e.message)
       |  });
       |
       |  if ($enableCom) {
       |    this.scalajsCom = {
       |      init: function(onMsg) {
       |        onMessage = onMsg;
       |        window.setTimeout(function() {
       |          for (var m in inMessages)
       |            onMessage(inMessages[m]);
       |          inMessages = null;
       |        });
       |      },
       |      send: function(msg) { outMessages.push(msg); }
       |    }
       |  }
       |
       |  this.scalajsPlayWrightInternalInterface = {
       |    fetch: function() {
       |      var res = {
       |        consoleLog: consoleLog.slice(),
       |        consoleError: consoleError.slice(),
       |        errors: errors.slice(),
       |        msgs: outMessages.slice()
       |      }
       |
       |      consoleLog.length = 0;
       |      consoleError.length = 0;
       |      errors.length = 0;
       |      outMessages.length = 0;
       |
       |      return res;
       |    },
       |    send: function(msg) {
       |      if (inMessages !== null) inMessages.push(msg);
       |      else onMessage(msg);
       |    }
       |  };
       |}).call(this)
    """.stripMargin

  private final class Streams(val out: PrintStream, val err: PrintStream):
    def close(): Unit =
      out.close()
      err.close()

  private def prepareStreams(config: RunConfig): Streams =
    val outPipe = Option.when(!config.inheritOutput) {
      val i = new PipedInputStream()
      val o = new PipedOutputStream(i)
      (i, o)
    }
    val errPipe = Option.when(!config.inheritError) {
      val i = new PipedInputStream()
      val o = new PipedOutputStream(i)
      (i, o)
    }
    config.onOutputStream.foreach(f => f(outPipe.map(_._1), errPipe.map(_._1)))
    val out = outPipe.fold[OutputStream](UnownedOutputStream(System.out))(_._2)
    val err = errPipe.fold[OutputStream](UnownedOutputStream(System.err))(_._2)
    Streams(new PrintStream(out), new PrintStream(err))

  private final class UnownedOutputStream(out: OutputStream) extends FilterOutputStream(out):
    override def close(): Unit = flush()

end PWRun

private class WindowOnErrorException(message: String) extends Exception(s"JS error:\n$message")
