package org.jpablo.graphexplorer.viewer

import buildinfo.BuildInfo
import com.raquo.laminar.api.L.*
import org.jpablo.graphexplorer.projects.{ProjectStorage, ProjectsDirectoryView}
import org.jpablo.graphexplorer.router.{Route, Router}
import org.jpablo.graphexplorer.viewer.backends.{DiagramFormat}
import org.jpablo.graphexplorer.viewer.backends.graphviz.Graphviz
import org.jpablo.graphexplorer.viewer.components.{Commands, RouterCommands, TopLevel}
import org.jpablo.graphexplorer.viewer.logging.Level
import org.jpablo.graphexplorer.viewer.state.{ProjectId, RightPanelSection, ViewerState}
import org.scalajs.dom.{document, window, URLSearchParams}
import org.jpablo.graphexplorer.viewer.models.ClientSize
import org.jpablo.graphexplorer.viewer.utils.ShareUrl
import scala.scalajs.js

import scala.scalajs.js.Date
import scala.concurrent.ExecutionContext.Implicits.global

object Viewer:
  private case class DesktopBridgeContext(
      path:     String,
      revision: Long,
      port:     Int,
      token:    String
  )

  private val DesktopDocumentChangedEventName         = "ge:document.changed"
  private val DesktopDocumentChangedFallbackEventName = "document.changed"
  private var desktopBridgeInstalled                  = false
  private var desktopBridgeTarget: Option[ViewerState] = None
  private var desktopBridgeContext: Option[DesktopBridgeContext] = None

  def main(args: Array[String]): Unit =
    given Owner    = unsafeWindowOwner
    val errors     = setupErrorHandling()
    val infos      = EventBus[String]()
    val router     = Router()
    val routerCmds = RouterCommands(router)

    var lastRightPanelSection = RightPanelSection.none
    var lastLeftPanelVisible  = false

    def setTheme(theme: String): Unit =
      document.documentElement.setAttribute("data-theme", theme)

    val viewerSettings = ProjectStorage.loadViewerSettings()
    viewerSettings.now().currentTheme.foreach(setTheme)

    // Determine ClientSize based on viewport width
    val mediaQueryList = window.matchMedia("(max-width: 768px)")
    val clientSize     = if (mediaQueryList.matches) ClientSize.Small else ClientSize.Normal

    // Parse log level from query string
    val queryParams = new URLSearchParams(window.location.search)
    val logLevel = Option(queryParams.get("logLevel"))
      .map(Level.fromString)
      .getOrElse(Level.None)

    // If a share URL (?dot=...) is present, resolve it immediately:
    val sharedDot = ShareUrl.readDotParam()
    sharedDot.foreach: dot =>
      ProjectStorage.findProjectByExactSource(dot) match
        case Some(existingId) =>
          router.navigateTo(Route.ProjectDetail(existingId.value))
        case None =>
          // Create a new project initialized with the provided DOT
          val newId = ProjectStorage.createProjectDirectoryEntry("Untitled")
          router.navigateTo(Route.ProjectDetail(newId.value, Some(dot)))

    Graphviz.build().foreach: (graphviz: Graphviz) =>
      dom.console.log("Graphviz (viz.js) initialized:", graphviz)
      printBanner()
      // Start the app after Graphviz is initialized

      val app =
        div(
          child <-- router.currentRoute.map:
            case Route.Home =>
              ProjectsDirectoryView(graphviz, router, routerCmds)

            case Route.ProjectDetail(id, source) =>
              val state =
                ViewerState(
                  projectId = ProjectId(id),
                  graphviz = graphviz,
                  writeText = window.navigator.clipboard.writeText,
                  setTheme = setTheme,
                  errorBus = errors,
                  infoBus = infos,
                  initialSource = source,
                  initialRightPanelSection = lastRightPanelSection,
                  initialLeftPanelVisible = lastLeftPanelVisible,
                  clientSize = clientSize,
                  logLevel = logLevel
                )
              // A bit hacky: we need to keep track of the last right panel section selected,
              // otherwise there's a noticeable transition none => something when switching diagrams
              state.rightPanelActiveSection.signal.changes.distinct.foreach(lastRightPanelSection = _)
              // Similarly track the left panel visibility state between diagrams
              state.leftPanelVisible.signal.changes.distinct.foreach(lastLeftPanelVisible = _)
              attachDesktopBridge(state)

              TopLevel(state, router, Commands(state, routerCmds))
        )

      render(document.querySelector("#app"), app)

  private def setupErrorHandling()(using Owner): EventBus[String] =
    val errors = EventBus[String]()
    AirstreamError.registerUnhandledErrorCallback(ex => errors.emit(ex.getMessage))
    windowEvents(_.onError).foreach(e => errors.emit(e.message))
    errors.events.foreach(e => dom.console.error("Error:", e))
    // debug focus events
    document.addEventListener("focusin", e => dom.console.debug("focusin:", e.target))
    document.addEventListener("focusout", e => dom.console.debug("focusout:", e.target))
    errors

  private def attachDesktopBridge(state: ViewerState): Unit =
    desktopBridgeTarget = Some(state)
    if !desktopBridgeInstalled then
      val handler: js.Function1[dom.Event, Unit] = event =>
        extractDesktopMessage(event).foreach: message =>
          updateDesktopBridgeContext(message)
          desktopBridgeTarget.foreach: current =>
            val detectedFormat = DiagramFormat.detect(message.text)
            current.setDiagramFormat(detectedFormat)
            current.sourceTextWriter.onNext(message.text)

      window.addEventListener(DesktopDocumentChangedEventName, handler)
      window.addEventListener(DesktopDocumentChangedFallbackEventName, handler)

      // Optional imperative fallback for desktop wrappers:
      // window.__graphExplorerDesktopBridge.pushText("...")
      val bridge = js.Dynamic.literal(
        pushText = (text: String) =>
          desktopBridgeTarget.foreach(_.sourceTextWriter.onNext(text)),
        saveCurrentText = () => saveCurrentTextToDesktop(),
        saveText = (text: String) => saveTextToDesktop(text)
      )
      js.Dynamic.global.window.updateDynamic("__graphExplorerDesktopBridge")(bridge)
      desktopBridgeInstalled = true
      dom.console.info("Desktop bridge listener installed.")

  private case class DesktopMessage(
      text:     String,
      path:     Option[String],
      revision: Option[Long],
      port:     Option[Int],
      token:    Option[String]
  )

  private def extractDesktopMessage(event: dom.Event): Option[DesktopMessage] =
    val raw = event.asInstanceOf[js.Dynamic]

    def asString(value: js.Any): Option[String] =
      if js.isUndefined(value) || value == null then None
      else if js.typeOf(value) == "string" then Some(value.asInstanceOf[String])
      else None

    def asLong(value: js.Any): Option[Long] =
      if js.isUndefined(value) || value == null then None
      else if js.typeOf(value) == "number" then Some(value.asInstanceOf[Double].toLong)
      else None

    def asInt(value: js.Any): Option[Int] =
      asLong(value).map(_.toInt)

    def field(value: js.Any, name: String): js.Any =
      if js.isUndefined(value) || value == null then js.undefined
      else value.asInstanceOf[js.Dynamic].selectDynamic(name)

    val detailValue   = raw.selectDynamic("detail")
    val payloadValue  = raw.selectDynamic("payload")
    val payloadText   = asString(field(payloadValue, "text"))
    val detailText    = asString(field(detailValue, "text"))
    val directPayload = asString(payloadValue)
    val detailPayload = asString(detailValue)

    val text = payloadText.orElse(detailText).orElse(directPayload).orElse(detailPayload)
    text.map: t =>
      DesktopMessage(
        text = t,
        path = asString(field(detailValue, "path")).orElse(asString(field(payloadValue, "path"))),
        revision = asLong(field(detailValue, "revision")).orElse(asLong(field(payloadValue, "revision"))),
        port = asInt(field(detailValue, "port")).orElse(asInt(field(payloadValue, "port"))),
        token = asString(field(detailValue, "token")).orElse(asString(field(payloadValue, "token")))
      )

  private def updateDesktopBridgeContext(message: DesktopMessage): Unit =
    for
      path <- message.path
      revision <- message.revision
      port <- message.port
      token <- message.token
    do desktopBridgeContext = Some(
      DesktopBridgeContext(
        path = path,
        revision = revision,
        port = port,
        token = token
      )
    )

  private def saveCurrentTextToDesktop(): Unit =
    desktopBridgeTarget.foreach: state =>
      saveTextToDesktop(state.sourceTextNow())

  private def saveTextToDesktop(text: String): Unit =
    (desktopBridgeTarget, desktopBridgeContext) match
      case (Some(state), Some(context)) =>
        val url = s"http://127.0.0.1:${context.port}/v1/document"
        val payload = js.Dynamic.literal(
          path = context.path,
          text = text,
          baseRevision = context.revision.toDouble,
          source = "ui"
        )
        val headers = new dom.Headers()
        headers.set("Authorization", s"Bearer ${context.token}")
        headers.set("Content-Type", "application/json")
        val requestInit = js.Dynamic
          .literal(
            method = "PUT",
            body = js.JSON.stringify(payload),
            headers = headers
          )
          .asInstanceOf[dom.RequestInit]

        dom.fetch(url, requestInit)
          .`then`[Unit]: response =>
            response.text().`then`[Unit]: raw =>
              if response.ok then
                val parsed    = js.JSON.parse(raw).asInstanceOf[js.Dynamic]
                val document  = parsed.selectDynamic("document")
                val revisionV = document.selectDynamic("revision")
                val pathV     = document.selectDynamic("path")
                val nextPath = if js.isUndefined(pathV) || pathV == null then context.path else pathV.asInstanceOf[String]
                val nextRevision =
                  if js.isUndefined(revisionV) || revisionV == null then context.revision
                  else revisionV.asInstanceOf[Double].toLong
                desktopBridgeContext = Some(context.copy(path = nextPath, revision = nextRevision))
                state.infoBus.emit("Saved to local file")
              else if response.status == 409 then
                state.errorBus.emit("Save conflict: file changed on disk. Reload and try again.")
              else
                state.errorBus.emit(s"Save failed (${response.status})")
              js.undefined
            js.undefined
          .`catch`: (err: Any) =>
            state.errorBus.emit(s"Save failed: ${String.valueOf(err)}")
            js.undefined
      case (Some(state), None) =>
        state.infoBus.emit("No active watched file for desktop save")
      case _ =>
        ()

  private def printBanner() =
    val banner =
      s"""
        |Welcome to Graph Explorer!
        |------------------------------
        |version:      ${BuildInfo.version}
        |scalaVersion: ${BuildInfo.scalaVersion}
        |sbtVersion:   ${BuildInfo.sbtVersion}
        |builtAt:      ${new Date(BuildInfo.builtAtString).toUTCString}
        |""".stripMargin
    dom.console.info(banner)
